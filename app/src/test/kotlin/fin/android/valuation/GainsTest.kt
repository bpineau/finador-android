package fin.android.valuation

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
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
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Correctness tests for the gains layer. Each fixture is built so the expected
 * gain is hand-computable, and the key invariant — external flows (a deposit)
 * must NOT count as a gain — is asserted directly.
 */
class GainsTest {
    private val tol = 1e-6
    private fun d(s: String) = LocalDate.parse(s)
    private fun eur(s: String) = Money(BigDecimal(s), "EUR")

    private var seq = 0
    private fun tx(
        date: String,
        account: String,
        asset: String?,
        kind: TxKind,
        qty: String = "0",
        amount: Money,
    ) = Tx(
        id = "tx-%04d".format(seq++), date = d(date), account = account, asset = asset,
        kind = kind, qty = BigDecimal(qty), amount = amount,
    )

    private fun period(report: GainsReport, label: String): PeriodGain =
        report.periods.first { it.label == label }

    private fun asset(report: GainsReport, name: String): AssetGain =
        report.assets.first { it.name == name }

    // ---- per-asset 1d / 7d absolute gain (single ccy, fx = 1) ----

    /**
     * One security, 10 shares, EUR = ref. Closes: 7d-ago = 100, 1d-ago = 108,
     * today = 110. Expected gains on the current 10 shares:
     *   1d = 10 × (110 − 108) = 20
     *   7d = 10 × (110 − 100) = 100
     */
    @Test fun perAssetSingleCcyGains() {
        seq = 0
        val today = d("2026-06-15")
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf("aa" to Asset("aa", AssetKind.SECURITY, "Alpha", ticker = "AA", ccy = "EUR", group = "g"))
        val txs = mapOf<String, Tx>().toMutableMap()
        listOf(tx("2026-01-01", "cto", "aa", TxKind.buy, "10", eur("1000"))).forEach { txs[it.id] = it }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-06-08"), 100.0), // 7d ago (today − 7)
                        PricePoint(d("2026-06-14"), 108.0), // 1d ago
                        PricePoint(d("2026-06-15"), 110.0), // today
                    ),
                ),
            ),
        )
        val report = Gains.report(book, market, today = today)
        val a = asset(report, "AA")
        assertEquals(20.0, a.d1!!, tol) // 10 × (110 − 108)
        assertEquals(100.0, a.d7!!, tol) // 10 × (110 − 100)
        assertEquals("EUR", report.referenceCcy)
    }

    // ---- portfolio absolute EXCLUDES a deposit made within the window ----

    /**
     * KEY CORRECTNESS TEST. A tracked cash account holds a security whose price
     * rises within the 7d window AND receives a deposit within the window. The
     * portfolio absolute gain must equal ONLY the price move, never the deposit.
     *
     * Setup (today = 2026-06-15, 7d-ago = 06-08):
     *   - 10 shares held throughout; close 06-08 = 100, today = 110 → +100 market.
     *   - a +5000 cash deposit on 06-10 (inside the window).
     * Expected 7d absolute = +100 (the deposit is neutralized, not a gain).
     */
    @Test fun portfolioAbsoluteExcludesDeposit() {
        seq = 0
        val today = d("2026-06-15")
        val accounts = mapOf("pea" to Account("pea", "PEA", "EUR", TaxRule.None))
        val assets = mapOf("aa" to Asset("aa", AssetKind.SECURITY, "Alpha", ticker = "AA", ccy = "EUR", group = "g"))
        val txs = mutableMapOf<String, Tx>()
        listOf(
            // Open the tracked envelope and buy well before the window.
            tx("2026-01-01", "pea", null, TxKind.statement, amount = eur("2000")), // makes cash tracked
            tx("2026-01-02", "pea", "aa", TxKind.buy, "10", eur("1000")),
            // A deposit INSIDE the 7d window — must not show as a gain.
            tx("2026-06-10", "pea", null, TxKind.deposit, amount = eur("5000")),
        ).forEach { txs[it.id] = it }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-06-08"), 100.0), // window start: 10 × 100 = 1000
                        PricePoint(d("2026-06-15"), 110.0), // today: 10 × 110 = 1100, +100 move
                    ),
                ),
            ),
        )
        val report = Gains.report(book, market, today = today)
        // The raw value delta is (1100 + 7000 cash) − (1000 + 2000 cash) = +5100,
        // but +5000 of that is the deposit (an external flow), so the GAIN is +100.
        assertEquals(100.0, period(report, "7d").absolute, 1e-2)
    }

    // ---- relative: null with < 2 points, finite with a multi-point window ----

    /**
     * A degenerate window (from == to, a single value point) yields a null
     * relative; a normal multi-day window yields a finite relative.
     */
    @Test fun relativeNullWhenTooFewPoints() {
        seq = 0
        // today = Jan 1 → the YTD window is [Jan 1, Jan 1] = one point → null relative.
        val today = d("2026-01-01")
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf("aa" to Asset("aa", AssetKind.SECURITY, "Alpha", ticker = "AA", ccy = "EUR", group = "g"))
        val txs = mutableMapOf<String, Tx>()
        listOf(tx("2025-06-01", "cto", "aa", TxKind.buy, "10", eur("1000"))).forEach { txs[it.id] = it }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(
                    listOf(
                        PricePoint(d("2025-06-01"), 100.0),
                        PricePoint(d("2026-01-01"), 120.0),
                    ),
                ),
            ),
        )
        val report = Gains.report(book, market, today = today)
        assertNull("single-point YTD window → null relative", period(report, "YTD").relative)
        assertEquals(0.0, period(report, "YTD").absolute, tol) // no window → graceful 0
        // The 7d window [Dec 25, Jan 1] has multiple points → a finite relative.
        assertNotNull("multi-point 7d window → finite relative", period(report, "7d").relative)
    }

    // ---- FX: a non-ref-ccy security's gain converts via the fx series ----

    /**
     * A USD security (ref = EUR), 10 shares. Closes 1d-ago = 100, today = 110 USD.
     * FX (value of 1 unit in USD) at both dates: USD = 1, EUR = 1.25 → rate
     * USD→EUR = 1 / 1.25 = 0.8. Expected 1d gain in EUR:
     *   10 × (110 × 0.8 − 100 × 0.8) = 10 × (88 − 80) = 80.
     */
    @Test fun perAssetFxConversion() {
        seq = 0
        val today = d("2026-06-15")
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf("uu" to Asset("uu", AssetKind.SECURITY, "USco", ticker = "UU", ccy = "USD", group = "g"))
        val txs = mutableMapOf<String, Tx>()
        // Buy priced in EUR cash, qty 10; the gain math only needs USD closes + FX.
        listOf(tx("2026-01-01", "cto", "uu", TxKind.buy, "10", eur("800"))).forEach { txs[it.id] = it }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "uu" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-06-14"), 100.0), // 1d ago, USD
                        PricePoint(d("2026-06-15"), 110.0), // today, USD
                    ),
                ),
            ),
            fx = mapOf(
                // value of one EUR in USD = 1.25 → USD→EUR rate = 1/1.25 = 0.8
                "EUR" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-06-14"), 1.25),
                        PricePoint(d("2026-06-15"), 1.25),
                    ),
                ),
            ),
        )
        val report = Gains.report(book, market, today = today)
        val a = asset(report, "UU")
        assertEquals("USD", a.ccy)
        assertEquals(80.0, a.d1!!, tol) // 10 × (110 − 100) × 0.8
    }
}
