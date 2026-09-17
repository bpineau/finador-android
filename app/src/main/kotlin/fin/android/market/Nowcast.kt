package fin.android.market

import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Estimates what a lagging fund is worth between its last published NAV and now.
 *
 * A French employee-savings fund ([AirfundFund]) is priced once a day and published about two
 * business days later, so its series is permanently two days short of the market. Its record names
 * a NOWCAST PROXY: a listed instrument whose moves, converted into the fund's currency, stand in
 * for it from the last published NAV onward. Two estimates come out of that, both flagged, neither
 * ever stored:
 *
 * - [forward] appends the business days the proxy has closed on since the last NAV, each carrying
 *   the proxy's return; [PriceSeries.estimatedFrom] marks the first of them.
 * - [live] adds today's session: the last daily value BEFORE the session, scaled by the proxy's
 *   move since the close that value stands on. Anchoring before the session rather than on the
 *   last point is what keeps a session from being counted twice once the proxy's daily close for
 *   the same day has landed.
 *
 * THE ANCHOR is that timing made explicit. Both estimates are the last published NAV times the
 * proxy's move SINCE THE PRINT THAT NAV WAS STRUCK ON, which the fund's record names
 * ([AirfundFund.navAnchor]): the proxy's close of the NAV's day by default, its OPEN for a fund
 * whose valuation rules price its holding at the opening of the valuation day. Anchoring such a
 * fund on the close would carry that session's open-to-close move as an offset for as long as the
 * NAV is the last one. The open reaches the estimate as a ratio ([DailyData.openFactors]), which is
 * currency- and adjustment-independent, so nothing here converts it; and it is a best effort: a day
 * the proxy did not trade, a source with no opening print and a failed fetch all leave the estimate
 * anchored on the close, never in error. An ESTIMATED day is anchored on the close whatever the
 * record says, being itself built from that close.
 *
 * What the estimate deliberately ignores: the fund's own charge (well under a cent over the few
 * days involved) and the proxy's tracking of the fund, which [AirfundFunds] quantifies per fund.
 * A proxy that cannot be read is not an error - the series simply ends at its last published NAV.
 */
object Nowcast {

    /**
     * The [fin.android.domain.MarketData.prices] key a proxy's own daily series is cached under.
     * A proxy is not an asset (the user need not hold it), so it cannot be keyed by asset id; the
     * `proxy:` prefix cannot collide with a ledger id, which is Crockford base32.
     */
    fun proxyKey(symbol: String): String = "proxy:$symbol"

    /**
     * [series] extended to the proxy's last close, each added day carrying the proxy's return
     * converted into the fund's currency, measured from the print the last NAV was struck on
     * ([openFactors] supplying the open-to-close ratios an [NavAnchor.OPEN] fund needs).
     *
     * Returns [series] untouched when there is nothing to add: no NAV yet, no proxy series, no
     * proxy close on or before the last NAV to anchor on, or no proxy close after it. [series] must
     * carry no estimate already (refresh strips them first), else the tail would compound.
     */
    fun forward(
        series: PriceSeries,
        proxy: PriceSeries?,
        fund: AirfundFund,
        converter: Converter,
        openFactors: PriceSeries? = null,
    ): PriceSeries {
        val last = series.points.lastOrNull() ?: return series
        if (proxy == null || proxy.points.isEmpty()) return series
        val (anchorClose, anchorOn) = proxy.at(last.date) ?: return series
        var base = convert(anchorClose, anchorOn, fund, converter) ?: return series
        if (base <= 0) return series
        // The published NAV was struck on the proxy's open of its own day, so the anchor moves back
        // there. A forward-filled close (a day the proxy did not trade) has no open of that day.
        if (fund.navAnchor == NavAnchor.OPEN && anchorOn == last.date) {
            base *= openFactor(openFactors, last.date)
        }
        val tail = proxy.points
            .filter { it.date.isAfter(last.date) }
            .mapNotNull { p ->
                val v = convert(p.close, p.date, fund, converter) ?: return@mapNotNull null
                PricePoint(p.date, last.close * v / base)
            }
        if (tail.isEmpty()) return series
        return series.copy(
            points = series.points + tail,
            estimatedFrom = tail.first().date,
            estimateProxy = fund.proxy,
        )
    }

    /**
     * [series] with today's session estimated from the proxy's live [quote], at the live [rate]
     * turning one unit of the proxy's currency into the fund's (null falls back to the daily rate
     * of the session day, which only misses the day's own FX move).
     *
     * The anchor is the fund's last daily value strictly BEFORE the quote's session, and the proxy
     * close that value stands on: same pair of dates on both sides, so the ratio measures the
     * session and nothing else. Returns [series] untouched when either side is missing.
     *
     * A session the fund has ALREADY PUBLISHED a NAV for is never estimated: the published value is
     * the fact, an estimate of the same day would replace it with a worse number AND label it an
     * estimate, and [PriceSeries.withoutEstimates] would then drop a real NAV on its way to disk.
     * Only the days past the last published one belong to the nowcast.
     *
     * The anchor moves to the proxy's OPEN ([openFactors]) only for an [NavAnchor.OPEN] fund whose
     * anchor day is a PUBLISHED NAV: an estimated day was itself built from the proxy's close of
     * that day, so it keeps it.
     */
    fun live(
        series: PriceSeries,
        proxy: PriceSeries?,
        fund: AirfundFund,
        quote: Quote,
        rate: Double?,
        converter: Converter,
        openFactors: PriceSeries? = null,
    ): PriceSeries {
        if (proxy == null || quote.price <= 0) return series
        val session = Instant.ofEpochSecond(quote.time).atZone(ZoneOffset.UTC).toLocalDate()
        val lastPublished = series.withoutEstimates().points.lastOrNull()?.date
        if (lastPublished != null && !session.isAfter(lastPublished)) return series
        val (anchor, on) = series.at(session.minusDays(1)) ?: return series
        if (anchor <= 0) return series
        val (proxyClose, proxyOn) = proxy.at(on) ?: return series
        var anchorProxy = convert(proxyClose, proxyOn, fund, converter) ?: return series
        if (anchorProxy <= 0) return series
        if (fund.navAnchor == NavAnchor.OPEN && !series.isEstimatedAt(on) && proxyOn == on) {
            anchorProxy *= openFactor(openFactors, on)
        }
        val liveRate = rate ?: converter.rate(fund.proxyCcy, fund.ccy, session) ?: return series
        val point = PricePoint(session, anchor * quote.price * liveRate / anchorProxy)
        val merged = series.merge(listOf(point))
        return merged.copy(
            estimatedFrom = minOf(series.estimatedFrom ?: point.date, point.date),
            estimateProxy = fund.proxy,
        )
    }

    /**
     * The live multiplier from [from] to [to], read off the batched FX quotes (`<CCY>USD=X`, the
     * value of one unit in USD, exactly like the cached FX series). Null when a leg has no live
     * quote, which sends the caller back to the daily rate.
     */
    fun liveRate(quotes: Map<String, Quote>, from: String, to: String): Double? {
        if (from == to) return 1.0
        val f = usdValue(quotes, from) ?: return null
        val t = usdValue(quotes, to) ?: return null
        if (t <= 0) return null
        return f / t
    }

    private fun usdValue(quotes: Map<String, Quote>, ccy: String): Double? {
        if (ccy == Converter.USD) return 1.0
        val q = quotes["${ccy}USD=X"] ?: return null
        if (q.currency != null && q.currency != Converter.USD) return null
        return q.price.takeIf { it > 0 }
    }

    /**
     * The factor that moves a value standing on the proxy's CLOSE of [day] to the same value
     * standing on its OPEN of that session, read off [factors] ([DailyData.openFactors]).
     *
     * 1.0 - the close, unchanged - whenever no usable factor exists for that EXACT day: no factor
     * series at all (a fetch failure, a provider serving no opening price), a day the proxy did not
     * trade, or a non-positive ratio. The estimate then keeps its close anchor rather than failing.
     */
    private fun openFactor(factors: PriceSeries?, day: LocalDate): Double {
        val points = factors?.points ?: return 1.0
        val i = points.binarySearchBy(day) { it.date }
        if (i < 0) return 1.0
        return points[i].close.takeIf { it > 0 } ?: 1.0
    }

    /** One proxy close in the fund's currency, at the rate of the close's own day. */
    private fun convert(close: Double, on: LocalDate, fund: AirfundFund, converter: Converter): Double? =
        converter.convert(close, fund.proxyCcy, fund.ccy, on)
}
