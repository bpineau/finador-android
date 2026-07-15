package fin.android.valuation

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import fin.android.domain.TaxRule
import fin.android.domain.Tx
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.pow

/**
 * Parity tests for the performance layer, ported from the Go `internal/perf`
 * (perf_test.go) and `internal/portfolio` (series_test.go) suites. Each ported
 * case keeps the Go expected number in a comment. Tolerances: 1e-4 in general,
 * 1e-2 for XIRR (bisection).
 */
class PerfTest {
    private val tol = 1e-4
    private val xirrTol = 1e-2

    private fun d(s: String) = LocalDate.parse(s)
    private fun p(date: String, v: Double) = PricePoint(d(date), v)
    private fun f(date: String, amt: Double) = Flow(d(date), amt)

    // ---- TWR (perf_test.go: TestTWR*) ----

    /** TestTWRNoFlows: 100→110 (+10 %), 110→99 (−10 %) → 0.99−1 = −1 %. */
    @Test fun twrNoFlows() {
        val pts = listOf(p("2026-06-01", 100.0), p("2026-06-02", 110.0), p("2026-06-03", 99.0))
        assertEquals(-0.01, Perf.twr(pts, emptyList()), tol)
    }

    /** TestTWRNeutralizesFlows: a day-3 contribution of 100 lifts the base from 105
     *  to 205, then +10 % → 225.5. Start-of-day flow: r3 = 225.5/(105+100) = +10 %.
     *  TWR = 1.05 × 1.10 − 1 = 0.155. */
    @Test fun twrNeutralizesFlows() {
        val pts = listOf(p("2026-06-01", 100.0), p("2026-06-02", 105.0), p("2026-06-03", 225.5))
        val flows = listOf(f("2026-06-03", 100.0))
        assertEquals(0.155, Perf.twr(pts, flows), tol)
    }

    /** TestTWRLargeFlowOnTinyBase (Go pofo): 100 sits, then 318000 arrives and is
     *  invested, closing 68.71 under cost. End-of-day (V−F)/V0 would divide that by
     *  the 100 base → a negative factor and a detonated chain; start-of-day keeps
     *  the day at its true ~−0.05 %. */
    @Test fun twrLargeFlowOnTinyBase() {
        val pts = listOf(p("2026-06-01", 100.0), p("2026-06-02", 317931.29))
        val flows = listOf(f("2026-06-02", 318000.0))
        val got = Perf.twr(pts, flows)
        assertEquals(317931.29 / 318100 - 1, got, tol)
        assertTrue("large same-day flow detonated the chain: $got", got > -0.01)
    }

    /** TestTWRSkipsZeroBase: day-2 base is 0 → skipped; day-3 = +10 %. */
    @Test fun twrSkipsZeroBase() {
        val pts = listOf(p("2026-06-01", 0.0), p("2026-06-02", 100.0), p("2026-06-03", 110.0))
        val flows = listOf(f("2026-06-02", 100.0))
        assertEquals(0.10, Perf.twr(pts, flows), tol)
    }

    // ---- DailyReturns (perf_test.go: TestDailyReturns*) ----

    /** TestDailyReturnsWeekdaysOnly: Fri (+2 %) and Mon (104/102−1) kept; Sat/Sun dropped. */
    @Test fun dailyReturnsWeekdaysOnly() {
        // 2026-06-05 is a Friday, 06 Sat, 07 Sun, 08 Mon.
        val pts = listOf(
            p("2026-06-04", 100.0), p("2026-06-05", 102.0),
            p("2026-06-06", 102.0), p("2026-06-07", 102.0), p("2026-06-08", 104.0),
        )
        val rs = Perf.dailyReturns(pts, emptyList())
        assertEquals(2, rs.size)
        assertEquals(0.02, rs[0], tol)
        assertEquals(104.0 / 102.0 - 1, rs[1], tol)
    }

    /** TestDailyReturnsAdjustsFlows: a Fri contribution of 100 lifts the base to 200,
     *  then +10 % → 220. Start-of-day flow: r = 220/(100+100) − 1 = +10 %. */
    @Test fun dailyReturnsAdjustsFlows() {
        val pts = listOf(p("2026-06-04", 100.0), p("2026-06-05", 220.0))
        val rs = Perf.dailyReturns(pts, listOf(f("2026-06-05", 100.0)))
        assertEquals(1, rs.size)
        assertEquals(0.10, rs[0], tol)
    }

    // ---- XIRR (perf_test.go: TestXIRR*) ----

    /** TestXIRRKnownValue: −1000 on Jan 1, +1100 on Dec 31 2026 (364 days).
     *  1.10 over 364/365.25 years → r = 1.10^(365.25/364) − 1 ≈ 0.1003. */
    @Test fun xirrKnownValue() {
        val r = Perf.xirr(listOf(f("2026-01-01", -1000.0), f("2026-12-31", 1100.0)))
        assertNotNull(r)
        assertEquals(1.10.pow(365.25 / 364) - 1, r!!, xirrTol)
    }

    /** TestXIRRWithIntermediateFlow: −1000 start, −500 mid-year, +1600 after a year.
     *  Independent truth: NPV(XIRR) ≈ 0 and r in [5 %, 10 %]. */
    @Test fun xirrWithIntermediateFlow() {
        val flows = listOf(f("2026-01-01", -1000.0), f("2026-07-01", -500.0), f("2027-01-01", 1600.0))
        val r = Perf.xirr(flows)
        assertNotNull(r)
        var npv = 0.0
        for (fl in flows) {
            val days = (fl.date.toEpochDay() - d("2026-01-01").toEpochDay()).toDouble()
            npv += fl.amount * (1 + r!!).pow(-days / 365.25)
        }
        assertEquals(0.0, npv, 1e-2)
        assertTrue("XIRR $r out of [5%,10%]", r!! in 0.05..0.10)
    }

    /** TestXIRRNoSolution: two same-sign flows → no IRR → null. */
    @Test fun xirrNoSolution() {
        assertNull(Perf.xirr(listOf(f("2026-01-01", -100.0), f("2026-06-01", -50.0))))
    }

    // ---- CAGR (perf_test.go: TestCAGR / TestCAGRGuards) ----

    /** TestCAGR: +21 % over 731 days → 1.21^(365.25/731) − 1; and 1y case. */
    @Test fun cagr() {
        assertEquals(1.21.pow(365.25 / 731) - 1, Perf.cagr(0.21, 731), tol)
        assertEquals(1.10.pow(365.25 / 365) - 1, Perf.cagr(0.10, 365), tol)
    }

    /** TestCAGRGuards: days ≤ 0 or totalReturn ≤ −1 → 0. */
    @Test fun cagrGuards() {
        assertEquals(0.0, Perf.cagr(0.10, 0), tol)
        assertEquals(0.0, Perf.cagr(-1.5, 100), tol)
    }

    // ---- Vol / Sharpe / Sortino (perf_test.go: TestVolSharpeSortino, TestVolEmptyAndSingle) ----

    /** TestVolSharpeSortino: hand-computed sample stdev × √252, Sharpe and Sortino at rf=2 %. */
    @Test fun volSharpeSortino() {
        val rs = listOf(0.01, -0.005, 0.002, 0.007, -0.003)
        val mean = (0.01 - 0.005 + 0.002 + 0.007 - 0.003) / 5
        var ss = 0.0
        for (r in rs) ss += (r - mean) * (r - mean)
        val wantVol = kotlin.math.sqrt(ss / 4) * kotlin.math.sqrt(252.0)
        assertEquals(wantVol, Perf.vol(rs), tol)

        val wantSharpe = (mean * 252 - 0.02) / wantVol
        assertEquals(wantSharpe, Perf.sharpe(rs, 0.02), tol)

        val rfDaily = 0.02 / 252
        var dss = 0.0
        for (r in rs) if (r < rfDaily) dss += (r - rfDaily) * (r - rfDaily)
        val wantDown = kotlin.math.sqrt(dss / rs.size) * kotlin.math.sqrt(252.0)
        assertEquals((mean * 252 - 0.02) / wantDown, Perf.sortino(rs, 0.02), tol)
    }

    /** TestVolEmptyAndSingle: vol/Sharpe of empty or single-point series → 0. */
    @Test fun volEmptyAndSingle() {
        assertEquals(0.0, Perf.vol(emptyList()), tol)
        assertEquals(0.0, Perf.vol(listOf(0.01)), tol)
        assertEquals(0.0, Perf.sharpe(emptyList(), 0.02), tol)
    }

    // ---- MaxDrawdown (perf_test.go: TestMaxDrawdown*) ----

    /** TestMaxDrawdown: 120 → 90 → −25 %. */
    @Test fun maxDrawdown() {
        val pts = listOf(
            p("2026-01-01", 100.0), p("2026-02-01", 120.0), p("2026-03-01", 90.0),
            p("2026-04-01", 100.0), p("2026-05-01", 125.0),
        )
        assertEquals(-0.25, Perf.maxDrawdown(pts), tol)
    }

    /** TestMaxDrawdownNotRecovered: 100 → 80 → −20 %. */
    @Test fun maxDrawdownNotRecovered() {
        val pts = listOf(p("2026-01-01", 100.0), p("2026-02-01", 80.0))
        assertEquals(-0.20, Perf.maxDrawdown(pts), tol)
    }

    /** TestMaxDrawdownReanchorsOnExactRetouch: retouching 120 re-anchors the peak,
     *  so the worst drawdown is the later 120 → 60 = −50 %, not 120 → 60 straddling
     *  a recovery. */
    @Test fun maxDrawdownReanchorsOnExactRetouch() {
        val pts = listOf(
            p("2026-01-01", 100.0), p("2026-02-01", 120.0), p("2026-03-01", 90.0),
            p("2026-04-01", 120.0), p("2026-05-01", 60.0), p("2026-06-01", 121.0),
        )
        assertEquals(-0.5, Perf.maxDrawdown(pts), tol)
    }

    // ---- compute() assembly: degenerate & full ----

    /** A single point → every metric null (too few points). */
    @Test fun degenerateSinglePoint() {
        val m = Perf.compute(listOf(p("2026-01-01", 100.0)), emptyList(), d("2026-01-01"), d("2026-01-01"), 0.0)
        assertNull(m.twr); assertNull(m.xirr); assertNull(m.cagr)
        assertNull(m.volatility); assertNull(m.sharpe); assertNull(m.sortino); assertNull(m.maxDrawdown)
    }

    /** Short window (< 90 days, < 1 year): TWR present, annualized risk/CAGR null, XIRR null < 30 days. */
    @Test fun shortWindowHidesAnnualized() {
        val pts = listOf(p("2026-01-01", 100.0), p("2026-01-10", 110.0))
        val m = Perf.compute(pts, emptyList(), d("2026-01-01"), d("2026-01-10"), 0.0)
        assertNotNull(m.twr)
        assertNull("CAGR needs ≥365 days", m.cagr)
        assertNull("vol needs ≥90 days", m.volatility)
        assertNull("XIRR needs ≥30-day window", m.xirr)
    }

    // ---- Series builder: flow-neutralized TWR over a real ledger ----

    /**
     * Mirrors series_test.go TestSeriesOpeningBuyValuedAtMarket: A is bought at
     * market and held flat; B is onboarded mid-window at a stale cost (500) while
     * the market says 1000. The opening flow is the +1000 MARKET value, and TWR
     * stays ~0 (the cost→market gap is NOT fabricated as performance).
     */
    @Test fun seriesOpeningBuyValuedAtMarket() {
        var seq = 0
        fun tx(date: String, asset: String, kind: TxKind, qty: String, amt: String) = Tx(
            id = "tx-%04d".format(seq++), date = d(date), account = "cto", asset = asset,
            kind = kind, qty = BigDecimal(qty), amount = Money(BigDecimal(amt), "EUR"),
        )
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf(
            "aa" to Asset("aa", AssetKind.SECURITY, "A", ccy = "EUR", group = "g"),
            "bb" to Asset("bb", AssetKind.SECURITY, "B", ccy = "EUR", group = "g"),
        )
        val txs = listOf(
            tx("2026-01-01", "aa", TxKind.buy, "10", "1000"),
            tx("2026-01-05", "bb", TxKind.buy, "10", "500"), // stale cost
        ).associateBy { it.id }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(listOf(PricePoint(d("2026-01-01"), 100.0))), // 10×100 flat
                "bb" to PriceSeries(listOf(PricePoint(d("2026-01-05"), 100.0))), // 10×100 at entry, flat
            ),
        )
        val series = SeriesBuilder(book, market, "EUR").build(d("2026-01-01"), d("2026-01-10"))
        // Exactly one external flow: B's entry at market value 1000 (not the 500 cost).
        assertEquals(1, series.flows.size)
        assertEquals(1000.0, series.flows[0].amount, 1e-2)
        // Everything flat at market → TWR ~0, not the +50 % the cost→market gap would fabricate.
        assertEquals(0.0, Perf.twr(series.points, series.flows), tol)
    }

    /**
     * Mirrors series_test.go TestSeriesPropertyRevaluationIsNotPerformance:
     * a property's +60000 re-statement is an adjustment flow, not a return → TWR ~0.
     */
    @Test fun seriesPropertyRevaluationIsNotPerformance() {
        var seq = 0
        fun stmt(date: String, amt: String) = Tx(
            id = "tx-%04d".format(seq++), date = d(date), account = "immo", asset = "house",
            kind = TxKind.statement, qty = BigDecimal.ZERO, amount = Money(BigDecimal(amt), "EUR"),
        )
        val accounts = mapOf("immo" to Account("immo", "Immo", "EUR", TaxRule.None))
        val assets = mapOf("house" to Asset("house", AssetKind.PROPERTY, "House", ccy = "EUR", group = "immo"))
        val txs = listOf(stmt("2026-01-01", "200000"), stmt("2026-03-01", "260000")).associateBy { it.id }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val series = SeriesBuilder(book, MarketData(), "EUR").build(d("2026-01-01"), d("2026-06-05"))
        assertEquals(0.0, Perf.twr(series.points, series.flows), tol)
    }

    /**
     * The flow-neutralization integration test on the rich valuation fixture
     * (mirror of value_test.go / series_test.go `valuationBook`). The whole-book
     * series endpoint must match the Go gross at 2026-06-05 (473890), and the
     * collected "All" external flows must match TestSeriesExternalFlowsAllScope.
     */
    @Test fun seriesAllScopeFlowsAndEndpointMatchGo() {
        val (book, market) = valuationBook()
        val series = SeriesBuilder(book, market, "EUR").build(d("2026-01-01"), d("2026-06-05"))
        // Endpoint gross == Valuator.value gross (golden 473890).
        assertEquals(473890.0, series.points.last().close, 0.01)
        // Go TestSeriesExternalFlowsAllScope flows:
        //   [0] 01-05 +12000 (livret cash adoption, D8)
        //   [1] 01-10 +10000 (pea deposit)
        //   [2] 01-20 +1100  (cto untracked buy)
        //   [3] 06-01 +50000 (maison re-base)
        val want = listOf(
            "2026-01-05" to 12000.0,
            "2026-01-10" to 10000.0,
            "2026-01-20" to 1100.0,
            "2026-06-01" to 50000.0,
        )
        assertEquals(want.size, series.flows.size)
        for (i in want.indices) {
            assertEquals(d(want[i].first), series.flows[i].date)
            assertEquals(want[i].second, series.flows[i].amount, 0.01)
        }
    }

    /**
     * Metrics over the rich fixture, starting at the earliest tx (V0 > 0, the
     * property statement 400000) and running > 365 days so risk and CAGR appear.
     * Cross-checked: TWR equals Perf.twr over the same series, XIRR is defined.
     */
    @Test fun metricsOverFixture() {
        val (book, market) = valuationBook()
        val from = d("2026-01-01") // earliest tx → V0 = 400000 > 0
        val to = d("2027-01-05") // > 365 calendar days, prices forward-fill past 06-05
        val m = Perf.metrics(book, market, referenceCcy = "EUR", from = from, to = to)
        // V0 > 0 and ≥ 365 / ≥ 90 days → every figure present.
        assertNotNull(m.twr); assertNotNull(m.xirr); assertNotNull(m.cagr)
        assertNotNull(m.volatility); assertNotNull(m.sharpe); assertNotNull(m.sortino)
        assertNotNull(m.maxDrawdown)
        // TWR is internally consistent with the series builder it wraps.
        val series = SeriesBuilder(book, market, "EUR").build(from, to)
        assertEquals(Perf.twr(series.points, series.flows), m.twr!!, tol)
        // maxDrawdown is non-positive.
        assertTrue(m.maxDrawdown!! <= 0.0)
    }

    /**
     * Window starting before any holding (V0 = 0): XIRR is undefined (needs a
     * positive opening value), the rest of the metrics still compute. Mirrors the
     * realistic guard in `periodRow` (pts[0].Value > 0).
     */
    @Test fun metricsZeroOpeningValueXirrNull() {
        val (book, market) = valuationBook()
        val m = Perf.metrics(book, market, referenceCcy = "EUR", from = d("2025-06-14"), to = d("2026-06-14"))
        assertNull("XIRR undefined when V0 ≤ 0", m.xirr)
        assertNotNull(m.twr)
    }

    /** risk-free rate read from book.config["risk-free"]: "2.4%" → 0.024. */
    @Test fun riskFreeFromConfig() {
        assertEquals(0.024, Perf.riskFreeFromConfig(mapOf("risk-free" to "2.4%")), tol)
        assertEquals(0.024, Perf.riskFreeFromConfig(mapOf("risk-free" to "2.4")), tol)
        assertEquals(0.0, Perf.riskFreeFromConfig(emptyMap()), tol)
    }

    // ---- fixture (mirror of ValuatorTest.valuationBook) ----

    private var nextSeq = 0
    private fun eur(s: String) = Money(BigDecimal(s), "EUR")
    private fun tx(date: String, account: String, asset: String?, kind: TxKind, qty: String = "0", amount: Money) =
        Tx(id = "tx-%04d".format(nextSeq++), date = d(date), account = account, asset = asset,
            kind = kind, qty = BigDecimal(qty), amount = amount)

    private fun valuationBook(): Pair<Book, MarketData> {
        nextSeq = 0
        val accounts = listOf(
            Account("pea", "PEA", "EUR", TaxRule.Gains(BigDecimal("0.172"))),
            Account("cto", "CTO", "EUR", TaxRule.Gains(BigDecimal("0.30"))),
            Account("livret", "Livret", "EUR", TaxRule.None),
            Account("immo", "Immo", "EUR", TaxRule.Gains(BigDecimal("0.30"))),
        ).associateBy { it.id }
        val assets = listOf(
            Asset("cw8", AssetKind.SECURITY, "CW8", ticker = "CW8.PA", ccy = "EUR", group = "actions/monde"),
            Asset("maison", AssetKind.PROPERTY, "Maison à Rénover", ccy = "EUR", group = "immo"),
        ).associateBy { it.id }
        val txs = listOf(
            tx("2026-01-10", "pea", null, TxKind.deposit, amount = eur("10000")),
            tx("2026-01-15", "pea", "cw8", TxKind.buy, "10", eur("5000")),
            tx("2026-02-15", "pea", "cw8", TxKind.buy, "5", eur("2750")),
            tx("2026-03-15", "pea", "cw8", TxKind.sell, "3", eur("1800")),
            tx("2026-01-20", "cto", "cw8", TxKind.buy, "2", eur("1100")),
            tx("2026-01-05", "livret", null, TxKind.statement, amount = eur("12000")),
            tx("2026-01-01", "immo", "maison", TxKind.statement, amount = eur("400000")),
            tx("2026-06-01", "immo", "maison", TxKind.statement, amount = eur("450000")),
        ).associateBy { it.id }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "cw8" to PriceSeries(listOf(PricePoint(d("2026-03-20"), 540.0), PricePoint(d("2026-06-05"), 560.0))),
            ),
        )
        return book to market
    }
}
