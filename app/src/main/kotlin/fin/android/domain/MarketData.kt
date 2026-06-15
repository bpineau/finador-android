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
 */
data class PriceSeries(
    val points: List<PricePoint> = emptyList(),
    val fetchedAt: LocalDate? = null,
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
)
