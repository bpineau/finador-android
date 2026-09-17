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
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One screen, one price. A price override (an off-hours print) is a valuation input, so every
 * figure shown NEXT TO that valuation must derive from it: the total, the position rows, the gains
 * table's value column and the detail page's price/value. Performance figures (the day move, the
 * period gains, the price history) stay on published closes.
 *
 * Mirrors the Go reference's decisions D36/D38 and how `internal/cli/value.go` applies
 * `portfolio.PriceOverride`.
 */
class PriceOverrideTest {
    private val tol = 1e-9
    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-06-15")

    /** One EUR security, 10 shares, closes 108 (1d ago) then 110 (today). */
    private fun book(ccy: String = "EUR") = Book(
        // A gains-taxed envelope at 0 %: the engine then tracks a cost basis (so the detail page's
        // unrealized figure exists) without any tax distorting the hand-computable totals.
        accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.Gains(BigDecimal.ZERO))),
        assets = mapOf("aa" to Asset("aa", AssetKind.SECURITY, "Alpha", ticker = "AA", ccy = ccy, group = "g")),
        txs = mapOf(
            "tx-1" to Tx(
                id = "tx-1", date = d("2026-01-01"), account = "cto", asset = "aa",
                kind = TxKind.buy, qty = BigDecimal("10"), amount = Money(BigDecimal("1000"), "EUR"),
            ),
        ),
        config = mapOf("currency" to "EUR"),
    )

    private fun market(fx: Map<String, PriceSeries> = emptyMap()) = MarketData(
        prices = mapOf(
            "aa" to PriceSeries(
                listOf(PricePoint(d("2026-06-14"), 108.0), PricePoint(today, 110.0)),
            ),
        ),
        fx = fx,
    )

    private val override = mapOf("aa" to 120.0)

    @Test fun theGainsTableValuesAtTheOverriddenPrice() {
        val book = book()
        val market = market()
        val valuation = Valuator.value(book, market, referenceCcy = "EUR", at = today, priceOverrides = override)
        val report = Gains.report(book, market, referenceCcy = "EUR", today = today, priceOverrides = override)

        val row = report.assets.first { it.name == "AA" }
        assertEquals(1200.0, row.value, tol) // 10 shares x 120, not x 110
        assertEquals(valuation.gross, report.assets.sumOf { it.value }, tol) // one screen, one total
    }

    @Test fun theOverrideIsReadInTheAssetsOwnCurrency() {
        // A USD line in an EUR book: the override is a USD price, crossed like any close.
        val book = book(ccy = "USD")
        val market = market(fx = mapOf("EUR" to PriceSeries(listOf(PricePoint(today, 1.08)))))
        val report = Gains.report(book, market, referenceCcy = "EUR", today = today, priceOverrides = override)

        assertEquals(10 * 120.0 / 1.08, report.assets.first { it.name == "AA" }.value, 1e-6)
    }

    @Test fun performanceFiguresStayOnPublishedCloses() {
        val book = book()
        val market = market()
        val plain = Gains.report(book, market, referenceCcy = "EUR", today = today)
        val overridden = Gains.report(book, market, referenceCcy = "EUR", today = today, priceOverrides = override)

        // The day move is a close-to-close figure, and the period gains are the flow-neutralized
        // TWR: an off-hours print measures the evening's thin book, not the portfolio's history.
        assertEquals(110.0 / 108.0 - 1, overridden.assets.first { it.name == "AA" }.d1!!, tol)
        assertEquals(plain.periods, overridden.periods)
    }

    @Test fun theDetailPagePricesAndValuesAtTheOverriddenPrice() {
        val book = book()
        val market = market()
        val plain = Gains.assetDetail(book, market, "EUR", today, "aa")!!
        val detail = Gains.assetDetail(book, market, "EUR", today, "aa", priceOverrides = override)!!

        assertEquals(120.0, detail.price!!, tol)
        assertEquals(1200.0, detail.value, tol)
        assertEquals(1200.0 - 1000.0, detail.unrealized!!, tol) // derives from the same price
        assertFalse(detail.priceEstimated) // an override is an observed print, not a nowcast
        // ... while the period table and the sparkline keep the published closes.
        assertEquals(plain.periods, detail.periods)
        assertEquals(plain.priceHistory, detail.priceHistory)
    }

    @Test fun noOverrideChangesNothing() {
        val book = book()
        val market = market()
        assertEquals(
            Gains.report(book, market, referenceCcy = "EUR", today = today),
            Gains.report(book, market, referenceCcy = "EUR", today = today, priceOverrides = emptyMap()),
        )
        assertEquals(
            Gains.assetDetail(book, market, "EUR", today, "aa"),
            Gains.assetDetail(book, market, "EUR", today, "aa", priceOverrides = emptyMap()),
        )
    }
}
