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
import fin.android.format.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Parity tests for the valuation layer: each Go `value_test.go` case rebuilt with
 * the same Book and price overrides, asserting the same Gross/Tax/Net (tolerance 0.01).
 * The Go fixture (`valuationBook`) is reproduced in [valuationBook] below.
 */
class ValuatorTest {
    private val tol = 0.01

    private fun d(s: String) = LocalDate.parse(s)
    private fun eur(s: String) = Money(BigDecimal(s), "EUR")

    private var nextSeq = 0
    /** Stable, date-monotonic id so (date, id) sort matches insertion intent. */
    private fun tx(
        date: String, account: String, asset: String?, kind: TxKind,
        qty: String = "0", amount: Money,
    ) = Tx(
        id = "tx-%04d".format(nextSeq++),
        date = d(date),
        account = account,
        asset = asset,
        kind = kind,
        qty = BigDecimal(qty),
        amount = amount,
    )

    /**
     * Mirror of Go's `valuationBook`:
     *   PEA (gains:17.2%, cash tracked via deposit), CTO (gains:30%, cash NOT tracked),
     *   Livret (none, cash statement 12000), Immo (gains:30%) with a property "maison"
     *   holding two statements (400000 on Jan 1, 450000 on Jun 1).
     *   CW8 (security, group actions/monde): pea buys 10 then 5, sells 3 → 12; cto buys 2.
     *   Price series: 540 on 2026-03-20, 560 on 2026-06-05.
     */
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
            Asset("maison", AssetKind.PROPERTY, "Maison à Achères", ccy = "EUR", group = "immo"),
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
                "cw8" to PriceSeries(
                    listOf(PricePoint(d("2026-03-20"), 540.0), PricePoint(d("2026-06-05"), 560.0)),
                ),
            ),
        )
        return book to market
    }

    private fun withTx(book: Book, vararg extra: Tx): Book {
        val merged = LinkedHashMap(book.txs)
        for (t in extra) merged[t.id] = t
        return book.copy(txs = merged)
    }

    // ---- Ported Go cases ----

    /**
     * TestValueAll (gains-taxed PEA security + tracked cash, gains CTO, none Livret, gains property).
     *   PEA: 12×560 = 6720 ; cash tracked = 10000 − 5000 − 2750 + 1800 = 4050
     *   CTO: 2×560 = 1120 ; Livret: 12000 ; Maison: 450000
     *   gross = 6720+4050+1120+12000+450000 = 473890
     *   Exact tax: PEA gains:17.2% base 10000 (deposits), value 10770 → 770×0.172 = 132.44
     *              CTO gains:30% base 1100 (buys−sells), value 1120 → 20×0.30 = 6
     *              Livret none → 0 ; Immo gains:30% base 400000, value 450000 → 50000×0.30 = 15000
     *   tax = 132.44 + 6 + 15000 = 15138.44
     */
    @Test fun valueAll() {
        val (book, market) = valuationBook()
        val v = Valuator.value(book, market, at = d("2026-06-05"))
        assertEquals(473890.0, v.gross, tol)
        assertEquals(132.44 + 6 + 15000, v.tax, tol)
        assertEquals(v.gross - v.tax, v.net, tol)
        // Per-line breakdown tax diverges from the exact envelope total → taxNote set.
        assertNotNull("taxNote expected (per-line approx ≠ envelope)", v.taxNote)
        val labels = v.lines.map { it.label }.toSet()
        for (want in listOf("actions", "immo", "cash")) {
            assertTrue("line $want absent in $labels", want in labels)
        }
    }

    /**
     * TestValueAtEarlierDate: at 2026-03-21 the forward-filled close is 540 (Mar 20),
     * the property uses its first statement (400000).
     *   PEA 12×540=6480, cash 4050 ; CTO 2×540=1080 ; livret 12000 ; maison 400000
     */
    @Test fun valueAtEarlierDate() {
        val (book, market) = valuationBook()
        val v = Valuator.value(book, market, at = d("2026-03-21"))
        assertEquals(6480.0 + 4050 + 1080 + 12000 + 400000, v.gross, tol)
    }

    /**
     * TestValueOtherCurrency: reference USD with EUR→USD = 1.10. Only the PEA scope in Go,
     * but here value() is whole-book; we check the PEA envelope line in USD instead.
     *   PEA gross (EUR) = 6720 + 4050 = 10770 → ×1.10 = 11847 (USD).
     * fx[EUR] = value of 1 EUR in USD = 1.10 (Converter crosses via USD).
     */
    @Test fun valueOtherCurrency() {
        val (book, market) = valuationBook()
        val withFx = market.copy(fx = mapOf("EUR" to PriceSeries(listOf(PricePoint(d("2026-01-01"), 1.10)))))
        val v = Valuator.value(book, withFx, referenceCcy = "USD", at = d("2026-06-05"), byGroup = false)
        val pea = v.lines.first { it.label == "PEA" }
        assertEquals((6720.0 + 4050) * 1.10, pea.gross, tol)
        assertEquals("USD", v.referenceCcy)
    }

    /**
     * TestValueAutoDividends: a Yahoo dividend of 2/share on cw8, ex-date 2026-03-01.
     *   PEA holds 15 shares at Mar 1 (10+5 bought, sell on Mar 15 is later) → +30 EUR cash.
     *   PEA gross = 6720 + 4050 + 30.
     * Then a manual Dividend on cw8 disables the auto one → +25 instead of +30.
     */
    @Test fun valueAutoDividends() {
        val (book, market) = valuationBook()
        val withDiv = market.copy(dividends = mapOf("cw8" to listOf(DividendEvent(d("2026-03-01"), 2.0))))
        val v = Valuator.value(withTx(book), withDiv, at = d("2026-06-05"), byGroup = false)
        assertEquals(6720.0 + 4050 + 30, v.lines.first { it.label == "PEA" }.gross, tol)

        val book2 = withTx(book, tx("2026-03-02", "pea", "cw8", TxKind.dividend, amount = eur("25")))
        val v2 = Valuator.value(book2, withDiv, at = d("2026-06-05"), byGroup = false)
        assertEquals(6720.0 + 4050 + 25, v2.lines.first { it.label == "PEA" }.gross, tol)
    }

    /**
     * TestAutoDividendWithholding: cw8 withholding 15% → 15×2×(1−0.15) = 25.50.
     */
    @Test fun autoDividendWithholding() {
        val (book, market) = valuationBook()
        val cw8 = book.assets.getValue("cw8").copy(withholding = 0.15)
        val book2 = book.copy(assets = book.assets + ("cw8" to cw8))
        val withDiv = market.copy(dividends = mapOf("cw8" to listOf(DividendEvent(d("2026-03-01"), 2.0))))
        val v = Valuator.value(book2, withDiv, at = d("2026-06-05"), byGroup = false)
        assertEquals(6720.0 + 4050 + 25.5, v.lines.first { it.label == "PEA" }.gross, tol)
    }

    /**
     * TestPropertyWithBuyNotDoubleCounted: a buy recorded on the property (e.g. notary)
     * must not value it twice — it stays valued by its statements (450000).
     */
    @Test fun propertyWithBuyNotDoubleCounted() {
        val (book, market) = valuationBook()
        val book2 = withTx(book, tx("2026-01-01", "immo", "maison", TxKind.buy, "1", eur("400000")))
        val v = Valuator.value(book2, market, at = d("2026-06-05"), byGroup = false)
        assertEquals(450000.0, v.lines.first { it.label == "Immo" }.gross, tol)
    }

    /**
     * TestNegativeEnvelopeBasisClamped: a 15000 PEA withdraw makes contributions negative.
     *   base = max(0, 10000−15000) = 0 ; cash = 10000−5000−2750+1800−15000 = −10950
     *   cw8 PEA = 12×560 = 6720 ; PEA gross = 6720 − 10950 = −4230
     *   gain = −4230 − 0 < 0 → tax = 0.
     */
    @Test fun negativeEnvelopeBasisClamped() {
        val (book, market) = valuationBook()
        val book2 = withTx(book, tx("2026-04-01", "pea", null, TxKind.withdraw, amount = eur("15000")))
        val v = Valuator.value(book2, market, at = d("2026-06-05"), byGroup = false)
        val pea = v.lines.first { it.label == "PEA" }
        assertEquals(-4230.0, pea.gross, tol)
        // PEA's exact envelope tax is 0; the only other taxed envelopes are CTO (6) and Immo (15000).
        assertEquals(6.0 + 15000, v.tax, tol)
    }

    /**
     * TestValueLinesByAccount: byGroup=false → one line per envelope carrying positions AND cash.
     *   PEA = 6720 + 4050 ; CTO = 1120 ; Livret = 12000 ; Immo = 450000 ; total 473890.
     */
    @Test fun valueLinesByAccount() {
        val (book, market) = valuationBook()
        val v = Valuator.value(book, market, at = d("2026-06-05"), byGroup = false)
        val got = v.lines.associate { it.label to it.gross }
        assertEquals(6720.0 + 4050, got.getValue("PEA"), tol)
        assertEquals(1120.0, got.getValue("CTO"), tol)
        assertEquals(12000.0, got.getValue("Livret"), tol)
        assertEquals(450000.0, got.getValue("Immo"), tol)
        assertEquals(473890.0, v.gross, tol)
    }

    /**
     * A cash-only / none-taxed account contributes its balance untaxed.
     * Livret line (none) → gross 12000, tax 0.
     */
    @Test fun noneAccountIsUntaxed() {
        val (book, market) = valuationBook()
        val v = Valuator.value(book, market, at = d("2026-06-05"), byGroup = false)
        val livret = v.lines.first { it.label == "Livret" }
        assertEquals(12000.0, livret.gross, tol)
        assertEquals(0.0, livret.tax, tol)
    }

    /** A value-taxed (PER-like) envelope taxes the whole value, not the gain. */
    @Test fun valueTaxedEnvelope() {
        nextSeq = 0
        val accounts = mapOf(
            "per" to Account("per", "PER", "EUR", TaxRule.Value(BigDecimal("0.30"))),
        )
        val assets = mapOf(
            "cw8" to Asset("cw8", AssetKind.SECURITY, "CW8", ccy = "EUR", group = "actions"),
        )
        val txs = listOf(
            tx("2026-01-10", "per", null, TxKind.deposit, amount = eur("10000")),
            tx("2026-01-15", "per", "cw8", TxKind.buy, "10", eur("5000")),
        ).associateBy { it.id }
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(prices = mapOf("cw8" to PriceSeries(listOf(PricePoint(d("2026-06-01"), 600.0)))))
        val v = Valuator.value(book, market, at = d("2026-06-05"))
        // security 10×600 = 6000 ; cash 10000 − 5000 = 5000 ; gross = 11000.
        assertEquals(11000.0, v.gross, tol)
        // value:30% taxes the whole value → 11000 × 0.30 = 3300. Per-line == envelope → no note.
        assertEquals(3300.0, v.tax, tol)
        assertNull(v.taxNote)
    }

    /** A held security with no price and no statement is counted as 0 (pragmatic, flagged). */
    @Test fun missingPriceCountsAsZero() {
        nextSeq = 0
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf("xyz" to Asset("xyz", AssetKind.SECURITY, "XYZ", ccy = "EUR", group = "actions"))
        val txs = listOf(tx("2026-01-15", "cto", "xyz", TxKind.buy, "10", eur("5000")))
            .associateBy { it.id }
        val book = Book(accounts = accounts, assets = assets, txs = txs)
        val v = Valuator.value(book, MarketData(), at = d("2026-06-05"))
        assertEquals(0.0, v.gross, tol)
        assertEquals(0.0, v.tax, tol)
    }

    // ---- Sample-ledger valuation ----

    private fun sampleBytes(): ByteArray =
        javaClass.getResourceAsStream("/sample.ledger")!!.use { it.readBytes() }

    /**
     * Opens the committed sample ledger and values it with a CW8 close of 500 EUR (all-EUR,
     * so no FX). Sample contents (see SampleLedgerTest):
     *   PEA BforBank (gains:17.2%): deposit 10000, buy 20 CW8 @ 9000.
     *   Livret (none): cash statement 15000.
     *   "Appart Lyon" property: no statement → excluded (no value).
     *
     * Derivation (at 2026-12-31):
     *   PEA cw8 = 20 × 500 = 10000.
     *   PEA cash tracked (has a deposit) = 10000 (deposit) − 9000 (buy) = 1000.
     *   Livret cash = 15000 (statement). Property has no statement → not a position.
     *   gross = 10000 + 1000 + 15000 = 26000.
     *   PEA exact envelope tax: gains:17.2%, base = deposits 10000, value = 10000 + 1000 = 11000
     *     → max(0, 11000 − 10000) × 0.172 = 1000 × 0.172 = 172.
     *   Livret none → 0. Total tax = 172. net = 26000 − 172 = 25828.
     */
    @Test fun sampleLedgerValuation() {
        val ledger = Ledger.open(sampleBytes(), "finador-format-spec-v3")
        val cw8Id = "06fc2cjxndn8wez8qqhh0a0"
        val market = MarketData(
            prices = mapOf(cw8Id to PriceSeries(listOf(PricePoint(d("2024-12-31"), 500.0)))),
        )
        val v = Valuator.value(ledger.book, market, at = d("2026-12-31"))
        assertEquals(26000.0, v.gross, tol)
        assertEquals(172.0, v.tax, tol)
        assertEquals(25828.0, v.net, tol)
        // The property carries no statement, so it produces no position.
        assertTrue(v.positions.none { it.kind == "property" })
        // Both the PEA security/cash and the Livret cash appear.
        assertTrue(v.positions.any { it.kind == "security" && it.gross == 10000.0 })
        assertTrue(v.positions.any { it.kind == "cash" && it.gross == 1000.0 })
        assertTrue(v.positions.any { it.kind == "cash" && it.gross == 15000.0 })
    }
}
