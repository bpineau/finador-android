// Package valuation replays the ledger and values the whole patrimoine at a date,
// in one reference currency. It mirrors the Go `internal/portfolio` package
// (replay.go, value.go, breakdown.go, scope.go) faithfully: same holdings replay,
// same per-envelope tax rules, same per-line approximation with a divergence note.
//
// Money is a Double here (matching Go's float64); quantities stay BigDecimal.
package fin.android.valuation

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.TaxRule
import fin.android.domain.Tx
import fin.android.domain.TxKind
import fin.android.market.Converter
import java.math.BigDecimal
import java.time.LocalDate

/** One valued position. [assetId] is null for an envelope's tracked cash. */
data class Position(
    val accountId: String,
    val accountName: String,
    val assetId: String?,
    val assetName: String,
    val kind: String, // "cash" | "security" | "property"
    val group: String?,
    val qty: BigDecimal,
    val ccy: String,
    val gross: Double,
    val tax: Double,
    val net: Double,
    val costBasis: Double?,
)

/** One breakdown line (a group root, a category, or an envelope). */
data class ValuationLine(val label: String, val gross: Double, val tax: Double, val net: Double)

/**
 * The value of the whole book at [asOf], in [referenceCcy]. Line taxes are the
 * per-position approximation; [gross]/[tax]/[net] use the exact per-account
 * envelope rule, and [taxNote] is set when the two visibly diverge (or when an
 * FX rate was missing - the affected value was counted as 0).
 */
data class Valuation(
    val asOf: LocalDate,
    val referenceCcy: String,
    val gross: Double,
    val tax: Double,
    val net: Double,
    val lines: List<ValuationLine>,
    val positions: List<Position>,
    val taxNote: String?,
)

object Valuator {
    /**
     * Values [book] against [market] at [at], in [referenceCcy] (defaults to
     * `book.config["currency"]` then "EUR"). [byGroup] true breaks lines down by
     * the asset group root (default); false breaks them down by envelope, each
     * envelope line carrying its positions AND its cash (mirrors Go's
     * `WithLinesByAccount`).
     */
    fun value(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        at: LocalDate,
        byGroup: Boolean = true,
    ): Valuation {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"
        return Valuer(book, market, at, ccy, byGroup).value()
    }
}

private const val NOTE_TAX_APPROX =
    "total tax follows the per-account rule; the per-line breakdown is approximate"

/** Internal engine: a single throwaway valuation. Nothing is persisted. */
internal class Valuer(
    private val book: Book,
    market: MarketData,
    private val at: LocalDate,
    private val ccy: String,
    private val byGroup: Boolean,
) {
    private val fx = Converter(market.fx)
    private val prices = market.prices
    private val dividends = market.dividends

    /** True when an FX rate was missing and a value was counted as 0. */
    private var fxMissing = false

    fun value(): Valuation {
        val positions = mutableListOf<Position>()

        // 1. security positions (qty × price, converted at `at`).
        for (h in holdings()) {
            if (h.asset.kind == AssetKind.PROPERTY) continue // valued by statements below
            val gross = positionValue(h)
            val tax = positionTax(h.account, h.asset, gross)
            positions += Position(
                accountId = h.account.id,
                accountName = h.account.name,
                assetId = h.asset.id,
                assetName = h.asset.name,
                kind = "security",
                group = h.asset.group,
                qty = h.qty,
                ccy = h.asset.ccy,
                gross = gross,
                tax = tax,
                net = gross - tax,
                costBasis = if (h.account.tax is TaxRule.Gains) positionBasis(h.account.id, h.asset.id) else null,
            )
        }

        // 2. properties, valued by their latest statement.
        for (p in statementPairs()) {
            if (p.asset.kind != AssetKind.PROPERTY) continue
            val gross = statementValue(p.account.id, p.asset)
            val tax = propertyTax(p.account, p.asset, gross)
            positions += Position(
                accountId = p.account.id,
                accountName = p.account.name,
                assetId = p.asset.id,
                assetName = p.asset.name,
                kind = "property",
                group = p.asset.group,
                qty = BigDecimal.ZERO,
                ccy = p.asset.ccy,
                gross = gross,
                tax = tax,
                net = gross - tax,
                costBasis = if (p.account.tax is TaxRule.Gains) propertyBasis(p.account.id, p.asset.id) else null,
            )
        }

        // 3. cash of tracked envelopes.
        for (acc in book.accounts.values) {
            if (!cashTracked(acc.id)) continue
            val gross = cashValue(acc)
            if (gross == 0.0) continue
            val tax = if (acc.tax is TaxRule.Value) gross * rate(acc.tax) else 0.0
            positions += Position(
                accountId = acc.id,
                accountName = acc.name,
                assetId = null,
                assetName = "cash",
                kind = "cash",
                group = null,
                qty = BigDecimal.ZERO,
                ccy = acc.ccy,
                gross = gross,
                tax = tax,
                net = gross - tax,
                costBasis = null,
            )
        }

        // Lines: aggregate positions by label, preserving first-seen order.
        val lineOrder = mutableListOf<String>()
        val lineGross = LinkedHashMap<String, Double>()
        val lineTax = LinkedHashMap<String, Double>()
        var gross = 0.0
        var lineTaxTotal = 0.0
        val perAccount = LinkedHashMap<String, Double>()
        for (p in positions) {
            val label = lineLabel(p)
            if (label !in lineGross) lineOrder += label
            lineGross[label] = (lineGross[label] ?: 0.0) + p.gross
            lineTax[label] = (lineTax[label] ?: 0.0) + p.tax
            gross += p.gross
            lineTaxTotal += p.tax
            perAccount[p.accountId] = (perAccount[p.accountId] ?: 0.0) + p.gross
        }
        val lines = lineOrder.map {
            val g = lineGross.getValue(it)
            val t = lineTax.getValue(it)
            ValuationLine(it, g, t, g - t)
        }

        // All scope: the exact total tax follows the per-account envelope rule.
        var exactTax = 0.0
        for ((accId, accGross) in perAccount) {
            val acc = book.accounts[accId] ?: continue
            exactTax += accountTax(acc, accGross)
        }
        var note: String? = null
        val d = exactTax - lineTaxTotal
        if (d > 0.01 || d < -0.01) note = NOTE_TAX_APPROX
        if (fxMissing) {
            val missingNote = "some positions could not be priced or converted - counted as 0"
            note = if (note == null) missingNote else "$note; $missingNote"
        }

        return Valuation(
            asOf = at,
            referenceCcy = ccy,
            gross = gross,
            tax = exactTax,
            net = gross - exactTax,
            lines = lines,
            positions = positions,
            taxNote = note,
        )
    }

    // ---- replay (replay.go) ----

    private class Holding(val account: Account, val asset: Asset, val qty: BigDecimal)

    /** Folds buy/sell per (account, asset) up to `at`; non-positive dropped; first-seen order. */
    private fun holdings(): List<Holding> {
        val qty = LinkedHashMap<Pair<String, String?>, BigDecimal>()
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.asset == null) continue
            val k = t.account to t.asset
            when (t.kind) {
                TxKind.buy -> qty[k] = (qty[k] ?: BigDecimal.ZERO) + t.qty
                TxKind.sell -> qty[k] = (qty[k] ?: BigDecimal.ZERO) - t.qty
                else -> {}
            }
        }
        val out = mutableListOf<Holding>()
        for ((k, q) in qty) {
            if (q.signum() <= 0) continue // oversell = data error: never a negative position
            val acc = book.accounts[k.first] ?: continue
            val asset = book.assets[k.second] ?: continue
            out += Holding(acc, asset, q)
        }
        return out
    }

    /** Quantity of one asset inside one account at [d] (clamped to 0 if negative). */
    private fun quantity(acc: String, asset: String, d: LocalDate): BigDecimal {
        var q = BigDecimal.ZERO
        for (t in sortedTxs) {
            if (t.date.isAfter(d) || t.account != acc || t.asset != asset) continue
            when (t.kind) {
                TxKind.buy -> q += t.qty
                TxKind.sell -> q -= t.qty
                else -> {}
            }
        }
        return if (q.signum() < 0) BigDecimal.ZERO else q
    }

    /** True when the account's cash is tracked: any pure-cash statement/deposit/withdraw. */
    private fun cashTracked(acc: String): Boolean = book.txs.values.any {
        it.account == acc && it.asset == null &&
            (it.kind == TxKind.statement || it.kind == TxKind.deposit || it.kind == TxKind.withdraw)
    }

    /** Ledger in replay order: (date, id). */
    // Sorted once (was re-sorted on every helper call, incl. per dividend ex-date).
    private val sortedTxs: List<Tx> = book.txs.values.sortedWith(compareBy({ it.date }, { it.id }))

    // ---- statement pairs (value.go) ----

    private class StatementPair(val account: Account, val asset: Asset)

    /** Distinct (account, asset) couples with at least one statement dated ≤ at, first-seen. */
    private fun statementPairs(): List<StatementPair> {
        val seen = HashSet<Pair<String, String?>>()
        val out = mutableListOf<StatementPair>()
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.kind != TxKind.statement || t.asset == null) continue
            val k = t.account to t.asset
            if (!seen.add(k)) continue
            val acc = book.accounts[t.account] ?: continue
            val asset = book.assets[t.asset] ?: continue
            out += StatementPair(acc, asset)
        }
        return out
    }

    // ---- valuation (value.go) ----

    /**
     * market close → last statement of the pair (a NAV observation, scaled per share when the
     * quantity changed since) → the cost basis (a bought position is never worth 0 just because
     * nothing observed it yet, or the buy itself would read as a loss). Converted to ref ccy at
     * `at`. Mirrors Go `value.go positionValue`.
     */
    private fun positionValue(h: Holding): Double {
        val close = prices[h.asset.id]?.at(at)?.first
        if (close != null) {
            return toRef(toF(h.qty) * close, h.asset.ccy, at)
        }
        val tx = lastStatement(h.account.id, h.asset.id)
        if (tx != null) {
            var total = toRef(tx.amount.amount, tx.amount.ccy, at)
            val qAt = quantity(h.account.id, h.asset.id, tx.date)
            if (qAt.signum() > 0) total = total / toF(qAt) * toF(h.qty)
            return total
        }
        return positionBasis(h.account.id, h.asset.id)
    }

    private fun statementValue(acc: String, asset: Asset): Double {
        val tx = lastStatement(acc, asset.id) ?: return 0.0
        return toRef(tx.amount.amount, tx.amount.ccy, at)
    }

    private fun lastStatement(acc: String, asset: String): Tx? {
        var last: Tx? = null
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.kind != TxKind.statement || t.account != acc || t.asset != asset) continue
            last = t
        }
        return last
    }

    private fun firstStatement(acc: String, asset: String): Tx? {
        for (t in sortedTxs) {
            if (t.date.isAfter(at)) break
            if (t.kind == TxKind.statement && t.account == acc && t.asset == asset) return t
        }
        return null
    }

    // ---- per-position tax (value.go) ----

    private fun positionTax(acc: Account, asset: Asset, gross: Double): Double = when (acc.tax) {
        is TaxRule.None -> 0.0
        is TaxRule.Value -> gross * rate(acc.tax)
        is TaxRule.Gains -> maxOf(0.0, gross - positionBasis(acc.id, asset.id)) * rate(acc.tax)
    }

    /** Average-cost basis of the pair's trades, in ref ccy (flows converted at their date). */
    private fun positionBasis(acc: String, asset: String): Double {
        var qty = 0.0
        var basis = 0.0
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.account != acc || t.asset != asset) continue
            when (t.kind) {
                TxKind.buy -> {
                    basis += toRef(t.amount.amount, t.amount.ccy, t.date)
                    qty += toF(t.qty)
                }
                TxKind.sell -> {
                    if (qty <= 0) continue
                    val sold = minOf(toF(t.qty), qty)
                    basis -= basis * sold / qty
                    qty -= sold
                }
                else -> {}
            }
        }
        return basis
    }

    /** property gains are measured from the FIRST known estimate. */
    private fun propertyTax(acc: Account, asset: Asset, gross: Double): Double = when (acc.tax) {
        is TaxRule.None -> 0.0
        is TaxRule.Value -> gross * rate(acc.tax)
        is TaxRule.Gains -> maxOf(0.0, gross - propertyBasis(acc.id, asset.id)) * rate(acc.tax)
    }

    private fun propertyBasis(acc: String, asset: String): Double {
        val first = firstStatement(acc, asset) ?: return 0.0
        return toRef(first.amount.amount, first.amount.ccy, first.date)
    }

    // ---- exact envelope tax (value.go) ----

    private fun accountTax(acc: Account, gross: Double): Double = when (acc.tax) {
        is TaxRule.None -> 0.0
        is TaxRule.Value -> gross * rate(acc.tax)
        is TaxRule.Gains -> maxOf(0.0, gross - accountBasis(acc)) * rate(acc.tax)
    }

    /** Net external contributions (deposits−withdraws if cash tracked, else buys−sells), plus
     *  property first estimates; clamped to 0. */
    private fun accountBasis(acc: Account): Double {
        val tracked = cashTracked(acc.id)
        var basis = 0.0
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.account != acc.id) continue
            val sign = when {
                tracked && t.kind == TxKind.deposit -> 1.0
                tracked && t.kind == TxKind.withdraw -> -1.0
                !tracked && t.kind == TxKind.buy -> 1.0
                !tracked && t.kind == TxKind.sell -> -1.0
                else -> continue
            }
            basis += sign * toRef(t.amount.amount, t.amount.ccy, t.date)
        }
        for (p in statementPairs()) {
            if (p.account.id != acc.id || p.asset.kind != AssetKind.PROPERTY) continue
            val first = firstStatement(acc.id, p.asset.id) ?: continue
            basis += toRef(first.amount.amount, first.amount.ccy, first.date)
        }
        return maxOf(0.0, basis)
    }

    // ---- cash (value.go) ----

    /** Anchor on the last cash statement ≤ at, then post-anchor flows + auto-dividends. */
    private fun cashValue(acc: Account): Double {
        var balance = 0.0
        var anchor: LocalDate? = null
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.account != acc.id || t.asset != null || t.kind != TxKind.statement) continue
            balance = convert(t.amount.amount.toDouble(), t.amount.ccy, acc.ccy, t.date)
            anchor = t.date
        }
        for (t in sortedTxs) {
            if (t.date.isAfter(at) || t.account != acc.id) continue
            if (anchor != null && !anchor.isBefore(t.date)) continue // already in the anchor statement
            val sign = when (t.kind) {
                TxKind.deposit, TxKind.sell, TxKind.dividend -> 1.0
                TxKind.withdraw, TxKind.buy, TxKind.fee -> -1.0
                else -> continue
            }
            balance += sign * convert(t.amount.amount.toDouble(), t.amount.ccy, acc.ccy, t.date)
        }
        balance += autoDividends(acc, anchor)
        return toRef(balance, acc.ccy, at)
    }

    /** Assets with at least one manual Dividend tx (their cached distributions are ignored). */
    private val manualDividendAssets: Set<String?> =
        book.txs.values.filter { it.kind == TxKind.dividend && it.asset != null }.map { it.asset }.toHashSet()

    /** Yahoo-known distributions for assets without any manual Dividend tx. */
    private fun autoDividends(acc: Account, after: LocalDate?): Double {
        val manual = manualDividendAssets
        var total = 0.0
        for ((id, events) in dividends) {
            if (id in manual) continue
            val asset = book.assets[id] ?: continue
            val withholding = asset.withholding ?: 0.0
            for (ev in events) {
                if (ev.exDate.isAfter(at)) continue
                if (after != null && !after.isBefore(ev.exDate)) continue
                val qty = quantity(acc.id, id, ev.exDate)
                if (qty.signum() == 0) continue
                total += convert(toF(qty) * ev.amount * (1 - withholding), asset.ccy, acc.ccy, ev.exDate)
            }
        }
        return total
    }

    // ---- lines (scope.go) ----

    /** Default label: asset group root (or "(ungrouped)"); cash → "cash".
     *  By-account: the envelope name. */
    private fun lineLabel(p: Position): String {
        if (byGroup) {
            if (p.assetId == null) return "cash"
            val g = p.group
            if (g.isNullOrEmpty()) return "(ungrouped)"
            return g.substringBefore('/').lowercase()
        }
        return p.accountName
    }

    // ---- helpers ----

    private fun rate(t: TaxRule): Double = when (t) {
        is TaxRule.Gains -> t.rate.toDouble()
        is TaxRule.Value -> t.rate.toDouble()
        is TaxRule.None -> 0.0
    }

    private fun toF(d: BigDecimal): Double = d.toDouble()

    /** Convert [amount] from [from] to [to] at [d]; missing rate → 0, flagged. */
    private fun convert(amount: Double, from: String, to: String, d: LocalDate): Double {
        val r = fx.convert(amount, from, to, d)
        if (r == null) {
            fxMissing = true
            return 0.0
        }
        return r
    }

    /** Convert to the reference ccy. */
    private fun toRef(amount: Double, from: String, d: LocalDate): Double = convert(amount, from, ccy, d)

    private fun toRef(amount: BigDecimal, from: String, d: LocalDate): Double = toRef(amount.toDouble(), from, d)
}
