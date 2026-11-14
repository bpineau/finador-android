package fin.android.ui

import fin.android.domain.PricePoint
import fin.android.valuation.AssetDetail
import java.time.LocalDate

internal enum class AssetChartRange(val label: String, val period: String? = null) {
    W1("1w", "7d"), M1("1m", "1m"), M6("6m", "6m"), Y1("1y", "1y"), MAX("Max");

    fun cutoff(anchor: LocalDate): LocalDate? = when (this) {
        W1 -> anchor.minusWeeks(1)
        M1 -> anchor.minusMonths(1)
        M6 -> anchor.minusMonths(6)
        Y1 -> anchor.minusYears(1)
        MAX -> null
    }
}

/** A display projection of the detail table's price/FX return, as fractions from the window start. */
internal fun performanceChartPoints(detail: AssetDetail, range: AssetChartRange): List<PricePoint> {
    val end = detail.periodEnd ?: return emptyList()
    val history = detail.priceHistory.filter { !it.date.isAfter(end) }
    if (history.size < 2 || history.last().date != end) return emptyList()
    val cutoff = range.cutoff(end)
    val start: LocalDate
    val base: Double
    val finalReturn: Double
    if (cutoff == null) {
        start = history.first().date
        base = history.first().close
        finalReturn = history.last().close / base - 1
    } else {
        // The table crosses the native close at the cutoff's FX rate. On a non-trading day that
        // can differ from the last price-history bar: reusing that bar would disagree with it.
        finalReturn = detail.periods.firstOrNull { it.label == range.period }?.relative ?: return emptyList()
        start = cutoff
        base = history.last().close / (1 + finalReturn)
    }
    if (!base.isFinite() || base <= 0.0 || !finalReturn.isFinite()) return emptyList()
    val window = history.filter { it.date > start }
    if (window.isEmpty() || window.any { !it.close.isFinite() || it.close <= 0.0 }) return emptyList()
    return listOf(PricePoint(start, 0.0)) + window.map { point ->
        PricePoint(point.date, if (point.date == end) finalReturn else point.close / base - 1)
    }
}
