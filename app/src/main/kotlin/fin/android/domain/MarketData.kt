package fin.android.domain

import java.time.LocalDate

/**
 * One daily close. Closes are analytics data: a [Double] is fine - decimal exactness lives in the
 * ledger ([Money]), not in market quotes.
 */
data class PricePoint(val date: LocalDate, val close: Double)

/**
 * A date-sorted daily close series with forward-fill lookup. [fetchedAt] records the last refresh
 * day even when no new point appeared (week-ends) - staleness is judged on it, not on the last point.
 * Instances are immutable: [merge] returns a new series.
 *
 * [estimatedFrom] marks the tail that is a NOWCAST rather than an observation: the first date from
 * which every point was estimated from [estimateProxy] instead of published by the instrument's own
 * source. Only a fund published with a lag ever carries one (see `market/Nowcast.kt`). The estimate
 * is recomputed at every refresh and NEVER stored: [withoutEstimates] is the door every persisting
 * consumer walks through, and the cache sidecar walks through it for all of them.
 */
data class PriceSeries(
    val points: List<PricePoint> = emptyList(),
    val fetchedAt: LocalDate? = null,
    val estimatedFrom: LocalDate? = null,
    val estimateProxy: String? = null,
) {
    /** The last close at or before [d] (forward-fill), with its date; null if none or empty. */
    fun at(d: LocalDate): Pair<Double, LocalDate>? {
        var lo = 0
        var hi = points.size // binary search for the first point with date > d
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].date > d) hi = mid else lo = mid + 1
        }
        if (lo == 0) return null
        val p = points[lo - 1]
        return p.close to p.date
    }

    fun last(): PricePoint? = points.lastOrNull()

    /** True when the value at [d] is an estimate rather than a published close. */
    fun isEstimatedAt(d: LocalDate): Boolean = estimatedFrom?.let { !d.isBefore(it) } ?: false

    /**
     * This series without its nowcast tail: itself when it carries none, a copy ending at the last
     * published point otherwise. Storing or validating an estimate is what this prevents.
     */
    fun withoutEstimates(): PriceSeries {
        val from = estimatedFrom ?: return this
        return copy(
            points = points.filter { it.date.isBefore(from) },
            estimatedFrom = null,
            estimateProxy = null,
        )
    }

    /** Upserts [pts] by date, returning a new series kept sorted and deduplicated by date. */
    fun merge(pts: List<PricePoint>): PriceSeries {
        if (pts.isEmpty()) return this
        val byDate = LinkedHashMap<LocalDate, PricePoint>(points.size + pts.size)
        for (p in points) byDate[p.date] = p
        for (p in pts) byDate[p.date] = p
        val merged = byDate.values.sortedBy { it.date }
        return copy(points = merged)
    }
}

/** One gross per-share distribution. */
data class DividendEvent(val exDate: LocalDate, val amount: Double)

/**
 * The cached public market state. It lives inside the encrypted sidecar: the list of held tickers is
 * sensitive metadata. Everything here is refetchable - losing it costs one refresh, never user data.
 * Maps are keyed by asset id ([prices], [dividends]) or currency ([fx]); `fx[C]` is the value of one
 * unit of currency C in USD.
 */
data class MarketData(
    val prices: Map<String, PriceSeries> = emptyMap(),
    val fx: Map<String, PriceSeries> = emptyMap(),
    val dividends: Map<String, List<DividendEvent>> = emptyMap(),
) {
    /**
     * The same data with every nowcast tail dropped (see [PriceSeries.withoutEstimates]). What
     * persists must be what a source published: an estimate is cheap to recompute and would
     * otherwise linger as a fact long after the real NAV landed.
     */
    fun withoutEstimates(): MarketData = copy(prices = prices.mapValues { it.value.withoutEstimates() })
}
