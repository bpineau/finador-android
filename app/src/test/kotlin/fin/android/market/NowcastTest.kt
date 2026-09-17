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
 *
 * A second fixture, further down, covers the OPEN anchor of a fund struck at its proxy's opening
 * print: three NAVs, six proxy sessions with their opens and a flat cross, whose estimates are
 * computed by hand from the reference's formula.
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

    @Test fun liveNeverOverwritesAPublishedNav() {
        // The fund has caught up and published d3 itself: a real NAV outranks an estimate of the
        // same day, which would both replace it and then be stripped on the way to the cache.
        val published = navs.merge(listOf(PricePoint(d3, 52.5)))
        val out = Nowcast.live(published, proxy, fund, quoteAt(d3, 204.0), rate = 1 / 1.10, converter)
        assertEquals(published, out)
        assertNull(out.estimatedFrom)
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

    // --- A fund struck at its proxy's OPENING print (ERES_DATADOG, NavAnchor.OPEN). ---

    private val o1 = d("2026-09-07")
    private val o2 = d("2026-09-08")
    private val o3 = d("2026-09-09")
    private val o4 = d("2026-09-10")
    private val o5 = d("2026-09-11")
    private val o6 = d("2026-09-12")
    private val o7 = d("2026-09-13")

    private val ddog = AirfundFunds.byTicker("ERES_DATADOG")!!
    private val worldM = AirfundFunds.byTicker("ERESMONDEM")!! // the same fixture, close-anchored

    /** THE PARITY FIXTURE: three published NAVs, the last one on o3. */
    private val openNavs = PriceSeries(
        listOf(PricePoint(o1, 120.0), PricePoint(o2, 121.0), PricePoint(o3, 125.0)),
    )

    private val openDays = listOf(o1, o2, o3, o4, o5, o6)
    private val openCloses = listOf(100.0, 102.0, 125.0, 130.0, 140.0, 150.0)
    private val openOpens = listOf(99.0, 101.0, 120.0, 128.0, 138.0, 145.0)

    /** The proxy in USD, closing three days past the last NAV. */
    private val openProxy = PriceSeries(openDays.mapIndexed { i, day -> PricePoint(day, openCloses[i]) })

    /** Its sessions' open-to-close ratios, exactly as [DailyData.openFactors] carries them. */
    private val openRatios = PriceSeries(
        openDays.mapIndexed { i, day -> PricePoint(day, openOpens[i] / openCloses[i]) },
    )

    /** Flat FX, so what the estimates measure is the proxy's move: one euro buys 1.25 dollar. */
    private val flatFx = Converter(
        mapOf("EUR" to PriceSeries((0..8L).map { PricePoint(o1.plusDays(it), 1.25) })),
    )

    private fun ddogQuoteAt(day: LocalDate, price: Double) =
        Quote("DDOG", price, day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), "USD")

    /**
     * PARITY with the Go reference (pofo c5336ac6, `nowcast_anchor: "open"`), on literals computed
     * by hand from its formula: estimate = NAV(D) x proxy_now / (proxy_close(D) x open(D)/close(D)),
     * the denominator being the proxy's OPEN of the day the NAV was struck on.
     *
     * Here the last NAV is 125 on o3, where the proxy closed at 125 and opened at 120, so every
     * estimate divides by 120 and not by 125. The flat cross cancels on both legs.
     */
    @Test fun openAnchorParityWithTheGoFormula() {
        val out = Nowcast.forward(openNavs, openProxy, ddog, flatFx, openRatios)
        assertEquals(6, out.points.size)
        assertEquals(135.416666667, out.points[3].close, 1e-9) // 125 x 130/120
        assertEquals(145.833333333, out.points[4].close, 1e-9) // 125 x 140/120
        assertEquals(156.25, out.points[5].close, 1e-9) //         125 x 150/120
        assertEquals(o4, out.estimatedFrom)

        // The live session on o7, the proxy trading at 168: the anchor is still the published NAV
        // of o3 standing on the o3 OPEN, so 125 x 168/120 = 175.
        val live = Nowcast.live(openNavs, openProxy, ddog, ddogQuoteAt(o7, 168.0), 1 / 1.25, flatFx, openRatios)
        assertEquals(175.0, live.points.last().close, 1e-9)
        assertEquals(o7, live.estimatedFrom)
    }

    @Test fun aCloseAnchoredFundIgnoresTheOpenFactors() {
        // The same fixture read the other way: dividing by the o3 CLOSE of 125 leaves the proxy's
        // own closes, since that close happens to equal the NAV.
        val out = Nowcast.forward(openNavs, openProxy, worldM, flatFx, openRatios)
        assertEquals(listOf(130.0, 140.0, 150.0), out.points.drop(3).map { round(it.close) })
    }

    @Test fun theOpenAnchorFallsBackOnTheCloseWhenNoFactorIsUsable() {
        val withoutTheNavsDay = PriceSeries(openRatios.points.filter { it.date != o3 })
        val nonPositive = PriceSeries(openRatios.points.map { if (it.date == o3) PricePoint(o3, 0.0) else it })
        for (factors in listOf(null, PriceSeries(), withoutTheNavsDay, nonPositive)) {
            val out = Nowcast.forward(openNavs, openProxy, ddog, flatFx, factors)
            assertEquals(listOf(130.0, 140.0, 150.0), out.points.drop(3).map { round(it.close) })
        }
    }

    @Test fun theOpenAnchorFallsBackOnTheCloseWhenTheProxyDidNotTradeOnTheNavsDay() {
        // A forward-filled anchor has no opening print of the NAV's own day to move to.
        val gapped = PriceSeries(openProxy.points.filter { it.date != o3 })
        assertEquals(
            Nowcast.forward(openNavs, gapped, ddog, flatFx, null),
            Nowcast.forward(openNavs, gapped, ddog, flatFx, openRatios),
        )
    }

    @Test fun liveKeepsTheCloseOnAnEstimatedAnchorDay() {
        // The anchor day o6 is an estimate, itself built from the proxy's CLOSE of o6 (150), so the
        // session divides by that close: 156.25 x 165/150 = 171.875, never by the o6 open of 145.
        val estimated = Nowcast.forward(openNavs, openProxy, ddog, flatFx, openRatios)
        val out = Nowcast.live(estimated, openProxy, ddog, ddogQuoteAt(o7, 165.0), 1 / 1.25, flatFx, openRatios)
        assertEquals(171.875, out.points.last().close, 1e-9)
        assertEquals(o4, out.estimatedFrom)
    }

    @Test fun liveAnchorsOnTheOpenWhenTheAnchorDayIsAPublishedNav() {
        // The fund has caught up to o6, publishing 145 there: the anchor stands on the o6 OPEN
        // (145, ratio 145/150), so 145 x 168/145 = 168, where the close would give 162.4.
        val published = openNavs.merge(listOf(PricePoint(o6, 145.0)))
        val out = Nowcast.live(published, openProxy, ddog, ddogQuoteAt(o7, 168.0), 1 / 1.25, flatFx, openRatios)
        assertEquals(168.0, out.points.last().close, 1e-9)
        assertEquals(o7, out.estimatedFrom)
    }

    @Test fun liveFallsBackOnTheCloseWithoutFactors() {
        // Same quote as the parity test, no factors: 125 x 168/125 = 168 instead of 175.
        val out = Nowcast.live(openNavs, openProxy, ddog, ddogQuoteAt(o7, 168.0), 1 / 1.25, flatFx, null)
        assertEquals(168.0, out.points.last().close, 1e-9)
    }

    private fun round(v: Double) = Math.round(v * 1e9) / 1e9
}
