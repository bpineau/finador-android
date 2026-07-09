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
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Reproduction of the "1d shows FX drift, not the last session" bug.
 *
 * Real scenario (as observed 2026-07-09): a EUR-reference portfolio holding DDOG (USD).
 *   - DDOG closes: 2026-07-07 = 256.81, 2026-07-08 = 261.09 (a +1.67% session). The US
 *     market has NOT opened on 2026-07-09, so there is no 07-09 close.
 *   - EUR/USD (value of 1 EUR in USD) trades ~24/5: 07-07 = 1.14038, 07-08 missing,
 *     07-09 = 1.14416 (a fresh point for the running day).
 *
 * Google/Yahoo report DDOG's 1-day = +1.67% (07-08 close vs 07-07 close). The app, anchoring
 * the window on the calendar day (07-09), instead compares a stale 07-08 price at a fresh 07-09
 * FX rate against the 07-08 price at a stale 07-07 FX rate: a ~2-day FX drift on a flat price.
 */
class OneDayAnchorTest {
    private val tol = 1e-4
    private fun d(s: String) = LocalDate.parse(s)

    private fun fixture(): Pair<Book, MarketData> {
        val accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None))
        val assets = mapOf("dd" to Asset("dd", AssetKind.SECURITY, "Datadog", ticker = "DDOG", ccy = "USD", group = "g"))
        val txs = mapOf(
            "t0" to Tx("t0", d("2026-01-01"), "cto", "dd", TxKind.buy, BigDecimal("10"), Money(BigDecimal("2000"), "USD")),
        )
        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        val market = MarketData(
            prices = mapOf(
                "dd" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-07-07"), 256.81),
                        PricePoint(d("2026-07-08"), 261.09), // last real close; no 07-09 (market shut)
                    ),
                ),
            ),
            fx = mapOf(
                // value of 1 EUR in USD; 07-08 intentionally missing (as Yahoo returned it)
                "EUR" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-07-07"), 1.14038),
                        PricePoint(d("2026-07-09"), 1.14416),
                    ),
                ),
            ),
        )
        return book to market
    }

    /** DDOG's real last-session move is +1.67%; the app's 1d must reflect it, not FX drift. */
    @Test fun oneDayReflectsLastSessionNotFxDrift() {
        val (book, market) = fixture()
        val today = d("2026-07-09")

        // Native DDOG 1-day, for reference: 261.09 / 256.81 - 1.
        val nativeOneDay = 261.09 / 256.81 - 1 // ~ +0.01667

        val detail = Gains.assetDetail(book, market, referenceCcy = "EUR", today = today, assetId = "dd")!!
        val d1 = detail.periods.first { it.label == "1d" }.relative!!
        assertEquals("asset-detail 1d should be DDOG's last session (~+1.67%)", nativeOneDay, d1, tol)

        val report = Gains.report(book, market, referenceCcy = "EUR", today = today)
        val portfolio1d = report.periods.first { it.label == "1d" }.relative!!
        assertEquals("portfolio 1d should be DDOG's last session (~+1.67%)", nativeOneDay, portfolio1d, tol)
    }
}
