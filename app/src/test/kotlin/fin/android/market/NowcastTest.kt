package fin.android.market

import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Covers the nowcast contract, on the fixture the Go reference uses (`TestNowcastForwardExtends...`):
 * two NAVs at 50 and 51, a proxy closing at 100, 102, 104 and 106 over the same days and the two
 * that follow, a flat FX rate. The estimate must read 52 then 53, be flagged from its first day,
 * and never appear at all when the proxy cannot be read.
 */
class NowcastTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val d1 = d("2026-08-17")
    private val d2 = d("2026-08-18")
    private val d3 = d("2026-08-19")
    private val d4 = d("2026-08-20")
    private val d5 = d("2026-08-21")

    private val fund = AirfundFunds.byTicker("ERESMONDEM")!!

    /** Two published NAVs, the last one on d2. */
    private val navs = PriceSeries(listOf(PricePoint(d1, 50.0), PricePoint(d2, 51.0)))

    /** The proxy in USD, closing two days past the last NAV. */
    private val proxy = PriceSeries(
        listOf(
            PricePoint(d1, 100.0), PricePoint(d2, 102.0),
            PricePoint(d3, 104.0), PricePoint(d4, 106.0),
        ),
    )

    /**
     * The app's FX convention: `fx[C]` is the value of one unit of C in USD, so a EUR series at
     * 1.10 means one euro buys 1.10 dollar. Flat here, exactly as the Go fixture keeps its own rate
     * flat: what the test measures is the proxy's return, not the FX move.
     */
    private val converter = Converter(
        mapOf("EUR" to PriceSeries((1..10).map { PricePoint(d1.plusDays(it.toLong() - 1), 1.10) })),
    )

    private fun quoteAt(day: LocalDate, price: Double) =
        Quote("URTH", price, day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), "USD")

    @Test fun forwardExtendsToTheProxysLastClose() {
        val out = Nowcast.forward(navs, proxy, fund, converter)
        assertEquals(4, out.points.size)
        assertEquals(d3, out.points[2].date)
        assertEquals(52.0, out.points[2].close, 1e-9) // 51 * 104 / 102
        assertEquals(d4, out.points[3].date)
        assertEquals(53.0, out.points[3].close, 1e-9) // 51 * 106 / 102
        assertEquals(d3, out.estimatedFrom)
        assertEquals("URTH", out.estimateProxy)
        // The published days keep their published values.
        assertEquals(navs.points, out.points.take(2))
        assertFalse(out.isEstimatedAt(d2))
        assertTrue(out.isEstimatedAt(d3))
    }

    @Test fun noProxyLeavesTheSeriesAtItsLastNav() {
        for (p in listOf(null, PriceSeries())) {
            val out = Nowcast.forward(navs, p, fund, converter)
            assertEquals(navs, out)
            assertNull(out.estimatedFrom)
        }
    }

    @Test fun noProxyCloseToAnchorOnLeavesTheSeriesAlone() {
        // The proxy's history starts after the last NAV: nothing to measure the return against.
        val late = PriceSeries(listOf(PricePoint(d3, 104.0), PricePoint(d4, 106.0)))
        assertEquals(navs, Nowcast.forward(navs, late, fund, converter))
    }

    @Test fun noFxLeavesTheSeriesAlone() {
        assertEquals(navs, Nowcast.forward(navs, proxy, fund, Converter(emptyMap())))
    }

    @Test fun forwardOnAnEmptySeriesIsANoop() {
        assertEquals(PriceSeries(), Nowcast.forward(PriceSeries(), proxy, fund, converter))
    }

    @Test fun liveScalesTheLastValueByTheProxysSessionMove() {
        val estimated = Nowcast.forward(navs, proxy, fund, converter) // ends at 53 on d4
        // A new session on d5: the proxy trades at 159, half again its d4 close of 106.
        val out = Nowcast.live(estimated, proxy, fund, quoteAt(d5, 159.0), rate = 1 / 1.10, converter)
        assertEquals(5, out.points.size)
        assertEquals(d5, out.points.last().date)
        assertEquals(79.5, out.points.last().close, 1e-9) // 53 * 159 / 106
        assertEquals(d3, out.estimatedFrom) // still flagged from the first estimated day
        assertEquals("URTH", out.estimateProxy)
    }

    @Test fun liveDoesNotCountASessionTwice() {
        val estimated = Nowcast.forward(navs, proxy, fund, converter)
        // A quote for d4, the day the proxy has already closed on: the anchor must be d3's value
        // and d3's proxy close, so quoting the d4 close back reproduces the d4 estimate exactly.
        val out = Nowcast.live(estimated, proxy, fund, quoteAt(d4, 106.0), rate = 1 / 1.10, converter)
        assertEquals(4, out.points.size)
        assertEquals(53.0, round(out.points.last().close), 1e-9)
    }

    @Test fun liveFlagsAnOtherwiseUnestimatedSeries() {
        // The NAVs are up to date (no forward tail) but the session is running.
        val out = Nowcast.live(navs, proxy, fund, quoteAt(d3, 204.0), rate = 1 / 1.10, converter)
        assertEquals(102.0, out.points.last().close, 1e-9) // 51 * 204 / 102
        assertEquals(d3, out.estimatedFrom)
    }

    @Test fun liveWithoutAnythingToAnchorOnLeavesTheSeriesAlone() {
        assertEquals(navs, Nowcast.live(navs, null, fund, quoteAt(d3, 204.0), 1 / 1.10, converter))
        assertEquals(navs, Nowcast.live(navs, proxy, fund, quoteAt(d3, 0.0), 1 / 1.10, converter))
        // No FX at all, live or daily: no estimate rather than an unconverted one.
        assertEquals(
            navs,
            Nowcast.live(navs, proxy, fund, quoteAt(d3, 204.0), null, Converter(emptyMap())),
        )
    }

    @Test fun liveFallsBackOnTheDailyRateWhenNoFxQuoteCameBack() {
        val out = Nowcast.live(navs, proxy, fund, quoteAt(d3, 204.0), rate = null, converter)
        assertEquals(102.0, out.points.last().close, 1e-9) // same rate on both legs, so it cancels
    }

    @Test fun liveRateReadsTheBatchedFxQuotes() {
        val quotes = mapOf("EURUSD=X" to Quote("EURUSD=X", 1.10, 0L, "USD"))
        assertEquals(1 / 1.10, Nowcast.liveRate(quotes, "USD", "EUR")!!, 1e-12)
        assertEquals(1.10, Nowcast.liveRate(quotes, "EUR", "USD")!!, 1e-12)
        assertEquals(1.0, Nowcast.liveRate(quotes, "EUR", "EUR")!!, 1e-12)
        assertNull(Nowcast.liveRate(quotes, "USD", "CHF")) // no quote: back to the daily rate
    }

    @Test fun proxyKeysCannotCollideWithALedgerId() {
        // Ledger ids are Crockford base32, which has no colon.
        assertTrue(Nowcast.proxyKey("URTH").contains(':'))
        assertEquals("proxy:URTH", Nowcast.proxyKey("URTH"))
    }

    @Test fun withoutEstimatesDropsTheTail() {
        val estimated = Nowcast.forward(navs, proxy, fund, converter)
        val stripped = estimated.withoutEstimates()
        assertEquals(navs.points, stripped.points)
        assertNull(stripped.estimatedFrom)
        assertNull(stripped.estimateProxy)
        assertEquals(navs, navs.withoutEstimates()) // a published series is returned untouched
    }

    private fun round(v: Double) = Math.round(v * 1e9) / 1e9
}
