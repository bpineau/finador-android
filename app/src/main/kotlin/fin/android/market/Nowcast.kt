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
     * converted into the fund's currency.
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
    ): PriceSeries {
        val last = series.points.lastOrNull() ?: return series
        if (proxy == null || proxy.points.isEmpty()) return series
        val base = proxy.at(last.date)?.let { (close, on) -> convert(close, on, fund, converter) } ?: return series
        if (base <= 0) return series
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
     */
    fun live(
        series: PriceSeries,
        proxy: PriceSeries?,
        fund: AirfundFund,
        quote: Quote,
        rate: Double?,
        converter: Converter,
    ): PriceSeries {
        if (proxy == null || quote.price <= 0) return series
        val session = Instant.ofEpochSecond(quote.time).atZone(ZoneOffset.UTC).toLocalDate()
        val (anchor, on) = series.at(session.minusDays(1)) ?: return series
        if (anchor <= 0) return series
        val anchorProxy = proxy.at(on)?.let { (close, day) -> convert(close, day, fund, converter) } ?: return series
        if (anchorProxy <= 0) return series
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

    /** One proxy close in the fund's currency, at the rate of the close's own day. */
    private fun convert(close: Double, on: LocalDate, fund: AirfundFund, converter: Converter): Double? =
        converter.convert(close, fund.proxyCcy, fund.ccy, on)
}
