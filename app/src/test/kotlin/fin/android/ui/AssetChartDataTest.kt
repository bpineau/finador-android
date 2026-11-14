package fin.android.ui

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
import fin.android.valuation.Gains
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class AssetChartDataTest {
    private fun day(s: String) = LocalDate.parse(s)
    private fun point(date: String, value: Double) = PricePoint(day(date), value)
    private val book = Book(
        accounts = mapOf("account" to Account("account", "CTO Meridia", "USD", TaxRule.None)),
        assets = mapOf("asset" to Asset("asset", AssetKind.SECURITY, "Synthetic Fund", ticker = "GTWR", ccy = "USD")),
        txs = mapOf("buy" to Tx("buy", day("2025-01-01"), "account", "asset", TxKind.buy,
            BigDecimal.TEN, Money(BigDecimal("600"), "USD"))),
    )
    private val market = MarketData(
        prices = mapOf("asset" to PriceSeries(listOf(
            point("2025-06-27", 60.0), point("2025-07-01", 62.0),
            point("2026-05-29", 80.0), point("2026-06-01", 82.0),
            point("2026-06-22", 100.0), point("2026-06-24", 110.0),
            point("2026-06-30", 120.0), point("2026-07-03", 9999.0),
        ))),
        fx = mapOf("EUR" to PriceSeries(listOf(
            point("2025-06-27", 1.0), point("2025-06-30", 2.0),
            point("2026-05-29", 1.0), point("2026-05-30", 2.0),
            point("2026-06-01", 1.0), point("2026-06-23", 1.25),
            point("2026-06-24", 1.0), point("2026-07-01", 2.0),
        ))),
    )
    private fun detail(data: MarketData = market, override: Map<String, Double> = emptyMap()) =
        Gains.assetDetail(book, data, "EUR", day("2026-07-02"), "asset", priceOverrides = override)!!

    @Test fun endpointsMatchTheTableWithFxOnDaysWithoutAnAssetQuote() {
        val d = detail()
        val cases = listOf(
            Triple(AssetChartRange.W1, "7d", 0.5),
            Triple(AssetChartRange.M1, "1m", 2.0),
            Triple(AssetChartRange.Y1, "1y", 3.0),
        )
        for ((range, label, expected) in cases) {
            val points = performanceChartPoints(d, range)
            assertTrue(points.size >= 2)
            assertEquals(range.cutoff(day("2026-06-30")), points.first().date)
            assertEquals(0.0, points.first().close, 0.0)
            assertEquals(day("2026-06-30"), points.last().date)
            assertEquals(expected, points.last().close, 1e-12)
            assertEquals(d.periods.single { it.label == label }.relative!!, points.last().close, 1e-12)
        }
    }

    @Test fun maxUsesAvailableHistoryAndExcludesFutureAndOffHoursPrices() {
        val points = performanceChartPoints(detail(), AssetChartRange.MAX)
        assertTrue(points.size >= 2)
        assertEquals(day("2025-06-27"), points.first().date)
        assertEquals(0.0, points.first().close, 0.0)
        assertEquals(day("2026-06-30"), points.last().date)
        assertEquals(1.0, points.last().close, 1e-12)
        assertEquals(points, performanceChartPoints(detail(override = mapOf("asset" to 2000.0)), AssetChartRange.MAX))
    }

    @Test fun unavailableWindowDoesNotSilentlyUseMaxHistory() {
        val recent = market.copy(prices = mapOf("asset" to PriceSeries(listOf(
            point("2026-06-29", 100.0), point("2026-06-30", 120.0),
        ))))
        for (range in listOf(AssetChartRange.W1, AssetChartRange.M1, AssetChartRange.Y1)) {
            assertTrue(performanceChartPoints(detail(recent), range).isEmpty())
        }
        assertEquals(0.2, performanceChartPoints(detail(recent), AssetChartRange.MAX).last().close, 1e-12)
    }

    @Test fun flatAndFallingPricesRemainZeroBasedFiniteSeries() {
        for (end in listOf(100.0, 80.0)) {
            val data = MarketData(
                prices = mapOf("asset" to PriceSeries(listOf(point("2026-06-23", 100.0), point("2026-06-30", end)))),
                fx = mapOf("EUR" to PriceSeries(listOf(point("2026-06-01", 1.0)))),
            )
            val points = performanceChartPoints(detail(data), AssetChartRange.W1)
            assertEquals(2, points.size)
            assertEquals(0.0, points.first().close, 0.0)
            assertEquals(end / 100.0 - 1, points.last().close, 1e-12)
            assertTrue(points.all { it.close.isFinite() })
        }
    }
}
