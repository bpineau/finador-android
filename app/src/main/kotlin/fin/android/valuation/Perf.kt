// Package-internal performance layer. Mirrors the Go `internal/perf` package
// (perf.go, periods.go, report.go) and the external-flow separation of
// `internal/portfolio/series.go`: pure math on a daily value series plus the
// dated external cash-flows that TWR neutralizes and XIRR consumes.
//
// Money is a Double here (matching Go's float64); quantities stay BigDecimal.
package fin.android.valuation

import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.TxKind
import fin.android.market.Converter
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Summary performance figures over `[from, to]`. Nullable where undefined
 * (too few points, no sign change, etc.). Mirrors the Go `perf.Metrics`/`Report`
 * outputs: [twr] cumulative since inception, the rest annualized.
 */
data class PerfMetrics(
    val from: LocalDate,
    val to: LocalDate,
    val twr: Double?,
    val xirr: Double?,
    val cagr: Double?,
    val volatility: Double?,
    val sharpe: Double?,
    val sortino: Double?,
    val maxDrawdown: Double?,
)

/** One external in/out of the measured scope (positive = money in). */
internal data class Flow(val date: LocalDate, val amount: Double)

object Perf {
    /**
     * Builds the daily value series of the whole book in [referenceCcy] (defaults
     * to `book.config["currency"]` then "EUR") over `[from, to]`, then derives the
     * performance figures, mirroring the Go formulas. [riskFree] is the annualized
     * risk-free rate; when 0 (the default) and `book.config["risk-free"]` is set,
     * that value is used instead.
     */
    fun metrics(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        from: LocalDate,
        to: LocalDate,
        riskFree: Double = 0.0,
    ): PerfMetrics {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"
        val rf = if (riskFree != 0.0) riskFree else riskFreeFromConfig(book.config)
        val series = SeriesBuilder(book, market, ccy).build(from, to)
        return compute(series.points, series.flows, from, to, rf)
    }

    /**
     * The daily portfolio value (gross, in reference ccy) over `[from, to]`.
     * Useful for charts and as the TWR/volatility input. Days lacking price/FX
     * data contribute 0 (a curve must stay drawable), mirroring the Go series.
     */
    fun valueSeries(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        from: LocalDate,
        to: LocalDate,
    ): List<PricePoint> {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"
        return SeriesBuilder(book, market, ccy).build(from, to).points
    }

    // ---- metric assembly (report.go) ----

    /** Minimum track records before annualized figures mean anything. */
    private const val MIN_DAYS_FOR_RISK = 90
    private const val MIN_DAYS_FOR_CAGR = 365

    internal fun compute(
        points: List<PricePoint>,
        flows: List<Flow>,
        from: LocalDate,
        to: LocalDate,
        rf: Double,
    ): PerfMetrics {
        if (points.size < 2) {
            return PerfMetrics(from, to, null, null, null, null, null, null, null)
        }
        val twr = twr(points, flows)
        val returns = dailyReturns(points, flows)
        val days = (points.last().date.toEpochDay() - points.first().date.toEpochDay()).toInt()

        val xirr = xirrOfWindow(points, flows, from, to)

        var vol: Double? = null
        var sharpe: Double? = null
        var sortino: Double? = null
        if (days >= MIN_DAYS_FOR_RISK && returns.size >= 2) {
            vol = vol(returns)
            sharpe = sharpe(returns, rf)
            sortino = sortino(returns, rf)
        }
        val cagr = if (days >= MIN_DAYS_FOR_CAGR) cagr(twr, days) else null
        val dd = maxDrawdown(points)
        val maxDd = if (dd == 0.0) 0.0 else dd

        return PerfMetrics(
            from = from,
            to = to,
            twr = twr,
            xirr = xirr,
            cagr = cagr,
            volatility = vol,
            sharpe = sharpe,
            sortino = sortino,
            maxDrawdown = maxDd,
        )
    }

    /**
     * XIRR over the full window: V0 invested at the start, external flows as the
     * investor's outflows/inflows, the final value cashed out. Mirrors the Go
     * `periodRow` XIRR block: windows under 30 days or V0 ≤ 0 → null.
     */
    private fun xirrOfWindow(
        points: List<PricePoint>,
        flows: List<Flow>,
        from: LocalDate,
        to: LocalDate,
    ): Double? {
        if (points.size < 2) return null
        val v0 = points.first()
        if (v0.close <= 0) return null
        if (to.toEpochDay() - from.toEpochDay() < 30) return null
        val cfs = mutableListOf(Flow(v0.date, -v0.close))
        for (f in flows) cfs += Flow(f.date, -f.amount)
        cfs += Flow(points.last().date, points.last().close)
        return xirr(cfs)
    }

    // ---- pure math (perf.go) ----

    /**
     * TWR chain-links daily returns r_t = V_t / (V_{t−1} + F_t) − 1, neutralizing
     * external flows. Flows are booked at the start of their day, so a same-day
     * contribution earns that day and belongs in the return's base; dividing by
     * V_{t−1} alone would charge a large flow's first-day P/L against the tiny
     * pre-flow value and detonate the chain. Days with a non-positive base
     * (V_{t−1} + F_t) are skipped. Mirrors Go pofo/metrics.TWR (v0.1.1).
     */
    internal fun twr(points: List<PricePoint>, flows: List<Flow>): Double {
        val byDay = flowsByDay(flows)
        var total = 1.0
        for (i in 1 until points.size) {
            val base = points[i - 1].close + (byDay[points[i].date] ?: 0.0)
            if (base <= 0) continue
            total *= points[i].close / base
        }
        return total - 1
    }

    /**
     * Flow-adjusted weekday returns of a calendar-daily series: V_t/(V_{t−1}+F_t)−1,
     * the same start-of-day flow convention as [twr]. Week-ends are forward-filled
     * flats and dropped (≈252 returns a year, annualized with √252). Days with a
     * non-positive base (V_{t−1} + F_t) are skipped.
     */
    internal fun dailyReturns(points: List<PricePoint>, flows: List<Flow>): List<Double> {
        val byDay = flowsByDay(flows)
        val out = mutableListOf<Double>()
        for (i in 1 until points.size) {
            val base = points[i - 1].close + (byDay[points[i].date] ?: 0.0)
            if (base <= 0) continue
            val wd = points[i].date.dayOfWeek
            if (wd == DayOfWeek.SATURDAY || wd == DayOfWeek.SUNDAY) continue
            out += points[i].close / base - 1
        }
        return out
    }

    private fun flowsByDay(flows: List<Flow>): Map<LocalDate, Double> {
        val byDay = HashMap<LocalDate, Double>()
        for (f in flows) byDay[f.date] = (byDay[f.date] ?: 0.0) + f.amount
        return byDay
    }

    /**
     * XIRR solves the money-weighted annual rate by bisection. Cashflows follow
     * the investor's convention: invested money negative, final value positive.
     * Returns null when there are fewer than two flows or no sign change.
     */
    internal fun xirr(cashflows: List<Flow>): Double? {
        if (cashflows.size < 2) return null
        val t0 = cashflows[0].date
        fun npv(r: Double): Double {
            var sum = 0.0
            for (f in cashflows) {
                val years = (f.date.toEpochDay() - t0.toEpochDay()).toDouble() / 365.25
                sum += f.amount * (1 + r).pow(-years)
            }
            return sum
        }
        var lo = -0.9999
        var hi = 100.0
        var flo = npv(lo)
        val fhi = npv(hi)
        if (flo.isNaN() || fhi.isNaN() || flo * fhi > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            val fm = npv(mid)
            if (fm == 0.0 || hi - lo < 1e-10) return mid
            if (fm * flo < 0) {
                hi = mid
            } else {
                lo = mid
                flo = fm
            }
        }
        return (lo + hi) / 2
    }

    /** CAGR annualizes a total return over a calendar-day span. */
    internal fun cagr(totalReturn: Double, days: Int): Double {
        if (days <= 0 || totalReturn <= -1) return 0.0
        return (1 + totalReturn).pow(365.25 / days) - 1
    }

    /** Annualized sample standard deviation of daily returns. */
    internal fun vol(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0
        val m = mean(returns)
        var ss = 0.0
        for (r in returns) ss += (r - m) * (r - m)
        return sqrt(ss / (returns.size - 1)) * sqrt(252.0)
    }

    /** Arithmetic annualization of the mean daily excess return over the vol. */
    internal fun sharpe(returns: List<Double>, rfAnnual: Double): Double {
        val v = vol(returns)
        if (v == 0.0) return 0.0
        return (mean(returns) * 252 - rfAnnual) / v
    }

    /** Like Sharpe but the denominator is the downside deviation vs the daily rf target. */
    internal fun sortino(returns: List<Double>, rfAnnual: Double): Double {
        if (returns.isEmpty()) return 0.0
        val target = rfAnnual / 252
        var ss = 0.0
        for (r in returns) if (r < target) ss += (r - target) * (r - target)
        val down = sqrt(ss / returns.size) * sqrt(252.0)
        if (down == 0.0) return 0.0
        return (mean(returns) * 252 - rfAnnual) / down
    }

    private fun mean(xs: List<Double>): Double {
        var s = 0.0
        for (x in xs) s += x
        return s / xs.size
    }

    /**
     * Worst peak-to-trough loss of the series (negative; −0.25 = −25 %). A value
     * that regains exactly the peak re-anchors the peak: a drawdown must not
     * straddle a full recovery. Mirrors the Go `MaxDrawdown.Depth`.
     */
    internal fun maxDrawdown(points: List<PricePoint>): Double {
        if (points.isEmpty()) return 0.0
        var depth = 0.0
        var peak = points[0].close
        for (i in 1 until points.size) {
            val v = points[i].close
            if (v >= peak) {
                peak = v
                continue
            }
            if (peak <= 0) continue
            val d = (v - peak) / peak
            if (d < depth) depth = d
        }
        return depth
    }

    /** Annualized risk-free rate from `cfg["risk-free"]` ("2.4" or "2.4%" → 0.024); 0 if absent. */
    internal fun riskFreeFromConfig(cfg: Map<String, String>): Double {
        val s = (cfg["risk-free"] ?: "").trim().removeSuffix("%")
        if (s.isEmpty()) return 0.0
        return s.toDoubleOrNull()?.let { it / 100 } ?: 0.0
    }
}

/** A daily value curve plus the scope's external flows. */
internal data class Series(val points: List<PricePoint>, val flows: List<Flow>)

/**
 * Walks the ledger once and produces the daily value of the WHOLE BOOK (the "All"
 * scope) between from and to, plus the external flows. Mirrors the Go
 * `portfolio.Series` walker for `Scope.All`: deposits/withdraws and untracked-cash
 * trades cross the scope boundary as flows, while statements re-base value as
 * adjustment flows rather than performance. Reuses the same holding/cash/dividend
 * rules as [Valuer]. Days lacking price/FX data contribute 0.
 */
internal class SeriesBuilder(
    private val book: Book,
    private val market: MarketData,
    private val ccy: String,
) {
    private val fx = Converter(market.fx)
    private val prices = market.prices
    private val dividends = market.dividends

    fun build(from: LocalDate, to: LocalDate): Series {
        val w = Walker()
        val txs = sorted()
        val flows = mutableListOf<Flow>()
        val points = mutableListOf<PricePoint>()

        var ti = 0
        var d = from
        while (!d.isAfter(to)) {
            // Apply every transaction up to and including day d. Transactions
            // strictly after `from` are collected as flows.
            while (ti < txs.size && !d.isBefore(txs[ti].date)) {
                val collect = from.isBefore(txs[ti].date)
                w.applyTx(txs[ti], collect, flows)
                ti++
            }
            w.applyDividends(d, from.isBefore(d), flows)
            points += PricePoint(d, w.valueAt(d))
            d = d.plusDays(1)
        }
        return Series(points, flows)
    }

    private fun sorted() = book.txs.values.sortedWith(compareBy({ it.date }, { it.id }))

    private fun convert(amount: Double, from: String, to: String, at: LocalDate): Double =
        fx.convert(amount, from, to, at) ?: 0.0

    private fun toRef(amount: Double, from: String, at: LocalDate): Double = convert(amount, from, ccy, at)

    /** True when the account's cash is tracked: any pure-cash statement/deposit/withdraw. */
    private fun cashTracked(acc: String): Boolean = book.txs.values.any {
        it.account == acc && it.asset == null &&
            (it.kind == TxKind.statement || it.kind == TxKind.deposit || it.kind == TxKind.withdraw)
    }

    /** Yahoo-known distributions for assets without any manual Dividend tx. */
    private val manualDividendAssets: Set<String?> =
        book.txs.values.filter { it.kind == TxKind.dividend && it.asset != null }.map { it.asset }.toHashSet()

    private inner class PairState(val accId: String, val assetId: String) {
        var qty = 0.0
        var stmt: fin.android.domain.Money? = null // last seen statement (property/unpriced security)
    }

    private inner class AccountState(val accId: String, val ccyAcc: String) {
        val tracked = cashTracked(accId)
        var cash = 0.0 // balance in account currency, anchored on last statement
        var hadCashStmt = false
    }

    private inner class Walker {
        private val pairs = LinkedHashMap<Pair<String, String>, PairState>()
        private val accounts = LinkedHashMap<String, AccountState>()

        init {
            for (acc in book.accounts.values) accounts[acc.id] = AccountState(acc.id, acc.ccy)
        }

        private fun pair(accId: String, assetId: String): PairState? {
            val k = accId to assetId
            pairs[k]?.let { return it }
            if (book.accounts[accId] == null || book.assets[assetId] == null) return null
            val p = PairState(accId, assetId)
            pairs[k] = p
            return p
        }

        private fun addFlow(flows: MutableList<Flow>, d: LocalDate, amount: Double, collect: Boolean) {
            if (collect && amount != 0.0) flows += Flow(d, amount)
        }

        fun applyTx(t: fin.android.domain.Tx, collect: Boolean, flows: MutableList<Flow>) {
            val acc = accounts[t.account] ?: return
            val accCcy = book.accounts[t.account]?.ccy ?: return

            when (t.kind) {
                TxKind.buy, TxKind.sell -> {
                    val assetId = t.asset ?: return
                    val asset = book.assets[assetId] ?: return
                    val p = pair(t.account, assetId) ?: return
                    val disp = toRef(t.amount.amount.toDouble(), t.amount.ccy, t.date)
                    val sign = if (t.kind == TxKind.sell) -1.0 else 1.0
                    val qtyBefore = p.qty

                    if (asset.kind != AssetKind.PROPERTY) {
                        if (t.kind == TxKind.buy) {
                            p.qty += t.qty.toDouble()
                        } else if (p.qty > 0) {
                            p.qty -= minOf(t.qty.toDouble(), p.qty)
                        }
                    }

                    // Flow valued at the MARKET value of the shares transacted at t.date -
                    // the value crossing the scope boundary - not the cash amount; falls
                    // back to the cash amount when no price is known that day.
                    var flowVal = disp
                    if (asset.kind != AssetKind.PROPERTY) {
                        val close = prices[assetId]?.at(t.date)?.first
                        if (close != null) {
                            var qtyTx = t.qty.toDouble()
                            if (t.kind == TxKind.sell) qtyTx = minOf(qtyTx, qtyBefore)
                            flowVal = convert(qtyTx * close, asset.ccy, ccy, t.date)
                        }
                    }

                    if (acc.tracked) {
                        val cashAmt = convert(t.amount.amount.toDouble(), t.amount.ccy, accCcy, t.date)
                        if (t.kind == TxKind.buy) acc.cash -= cashAmt else acc.cash += cashAmt
                    }
                    // All scope: a trade is an external flow only on an untracked cash account.
                    if (!acc.tracked) addFlow(flows, t.date, sign * flowVal, collect)
                }

                TxKind.deposit, TxKind.withdraw -> {
                    val sign = if (t.kind == TxKind.withdraw) -1.0 else 1.0
                    val disp = toRef(t.amount.amount.toDouble(), t.amount.ccy, t.date)
                    val cashAmt = convert(t.amount.amount.toDouble(), t.amount.ccy, accCcy, t.date)
                    acc.cash += sign * cashAmt
                    addFlow(flows, t.date, sign * disp, collect)
                }

                TxKind.dividend -> {
                    val disp = toRef(t.amount.amount.toDouble(), t.amount.ccy, t.date)
                    if (acc.tracked) {
                        acc.cash += convert(t.amount.amount.toDouble(), t.amount.ccy, accCcy, t.date)
                    }
                    // All scope: revenue collected on an untracked account leaves the pocket.
                    if (!acc.tracked) addFlow(flows, t.date, -disp, collect)
                }

                TxKind.fee -> {
                    if (acc.tracked) {
                        acc.cash -= convert(t.amount.amount.toDouble(), t.amount.ccy, accCcy, t.date)
                    }
                    // never a flow: a cost must weigh on performance
                }

                TxKind.statement -> {
                    if (t.asset == null) {
                        // Pure cash statement: first reconciliation = adoption (a flow);
                        // later ones measure performance (e.g. livret interest).
                        if (acc.tracked) {
                            val newBalance = convert(t.amount.amount.toDouble(), t.amount.ccy, accCcy, t.date)
                            if (!acc.hadCashStmt) {
                                val currentDisp = convert(acc.cash, accCcy, ccy, t.date)
                                val newDisp = toRef(t.amount.amount.toDouble(), t.amount.ccy, t.date)
                                addFlow(flows, t.date, newDisp - currentDisp, collect)
                                acc.hadCashStmt = true
                            }
                            acc.cash = newBalance
                        }
                        return
                    }
                    val asset = book.assets[t.asset] ?: return
                    val p = pair(t.account, t.asset) ?: return
                    val isFirstStmt = p.stmt == null
                    val prevHeld = if (isFirstStmt) 0.0 else toRef(p.stmt!!.amount.toDouble(), p.stmt!!.ccy, t.date)
                    p.stmt = t.amount
                    val newDisp = toRef(t.amount.amount.toDouble(), t.amount.ccy, t.date)
                    // A statement re-declares a value: the gap since the previously-held
                    // value is an adjustment (a flow), not performance.
                    //   - property: always declaration-valued → re-baseline every statement
                    //   - security: only while it has no market price that day
                    when {
                        asset.kind == AssetKind.PROPERTY ->
                            addFlow(flows, t.date, newDisp - prevHeld, collect)
                        isFirstStmt -> {
                            val hasPx = prices[t.asset]?.at(t.date) != null
                            if (!hasPx && p.qty > 0) addFlow(flows, t.date, newDisp, collect)
                        }
                    }
                }
            }
        }

        /** Credits the day's automatic dividends and emits the matching scope flows. */
        fun applyDividends(d: LocalDate, collect: Boolean, flows: MutableList<Flow>) {
            for (p in pairs.values) {
                if (p.qty <= 0 || p.assetId in manualDividendAssets) continue
                val asset = book.assets[p.assetId] ?: continue
                val withholding = asset.withholding ?: 0.0
                for (ev in dividends[p.assetId] ?: emptyList()) {
                    if (ev.exDate != d) continue
                    val net = p.qty * ev.amount * (1 - withholding)
                    val disp = toRef(net, asset.ccy, d)
                    val acc = accounts[p.accId] ?: continue
                    if (acc.tracked) acc.cash += convert(net, asset.ccy, acc.ccyAcc, d)
                    // All scope: dividend collected on an untracked account leaves the pocket.
                    if (!acc.tracked) addFlow(flows, d, -disp, collect)
                }
            }
        }

        /** Gross value of the whole book at day d. */
        fun valueAt(d: LocalDate): Double {
            var gross = 0.0
            // Security / property positions.
            for (p in pairs.values) {
                val asset = book.assets[p.assetId] ?: continue
                var v = 0.0
                if (asset.kind == AssetKind.PROPERTY) {
                    p.stmt?.let { v = toRef(it.amount.toDouble(), it.ccy, d) }
                } else if (p.qty > 0) {
                    val close = prices[p.assetId]?.at(d)?.first
                    v = when {
                        close != null -> convert(p.qty * close, asset.ccy, ccy, d)
                        p.stmt != null -> toRef(p.stmt!!.amount.toDouble(), p.stmt!!.ccy, d)
                        else -> 0.0
                    }
                }
                gross += v
            }
            // Cash of tracked envelopes.
            for (acc in accounts.values) {
                if (!acc.tracked) continue
                gross += convert(acc.cash, acc.ccyAcc, ccy, d)
            }
            return gross
        }
    }
}
