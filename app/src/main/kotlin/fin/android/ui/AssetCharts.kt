package fin.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fin.android.domain.PricePoint
import fin.android.valuation.AssetDetail

private val priceRanges = listOf(AssetChartRange.M1, AssetChartRange.M6, AssetChartRange.Y1, AssetChartRange.MAX)
private val performanceRanges = listOf(AssetChartRange.W1, AssetChartRange.M1, AssetChartRange.Y1, AssetChartRange.MAX)

/** Discreet accent pills with explicit selection semantics and full touch targets. */
@Composable
private fun RangeChips(ranges: List<AssetChartRange>, selected: AssetChartRange, onSelect: (AssetChartRange) -> Unit) {
    Row(
        Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ranges.forEach { range ->
            val chosen = range == selected
            // The 48dp touch target is the Box; the pill hugs the label inside it, centred, so
            // the target's minimum size never stretches the pill or shifts the text.
            Box(
                Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(range) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    range.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .then(if (chosen) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun AssetPriceChart(detail: AssetDetail) {
    val all = detail.priceHistory
    if (all.size < 2) return
    var range by rememberSaveable(detail.assetId) { mutableStateOf(AssetChartRange.M6) }
    val points = remember(range, all) {
        val cutoff = range.cutoff(all.last().date)
        val filtered = if (cutoff == null) all else all.filter { !it.date.isBefore(cutoff) }
        if (filtered.size >= 2) filtered else all
    }
    DetailCard {
        Text("Price (${detail.referenceCcy})", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RangeChips(priceRanges, range) { range = it }
        AssetLineChart(points, "Price in ${detail.referenceCcy}, ${points.first().date} to ${points.last().date}")
        ChartDates(points)
    }
}

/** The period table's return, drawn from a zero baseline in the same reference currency. */
@Composable
internal fun AssetPerformanceChart(detail: AssetDetail) {
    var range by rememberSaveable(detail.assetId) { mutableStateOf(AssetChartRange.M1) }
    val points = remember(detail, range) { performanceChartPoints(detail, range) }
    DetailCard {
        Text("Performance (%)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RangeChips(performanceRanges, range) { range = it }
        if (points.size < 2) {
            Text("Not enough price or currency history for this period.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(formatGainPercent(points.last().close), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = gainLossColor(points.last().close))
            AssetLineChart(points,
                "Performance ${formatGainPercent(points.last().close)}, ${points.first().date} to ${points.last().date}",
                zeroLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatGainPercent(points.minOf { it.close }), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatGainPercent(points.maxOf { it.close }), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChartDates(points)
        }
    }
}

@Composable
private fun ChartDates(points: List<PricePoint>) {
    Text("${points.first().date} → ${points.last().date} · ${points.size} pts",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Shared price/performance drawing; the fraction values never enter persisted market data. */
@Composable
private fun AssetLineChart(points: List<PricePoint>, description: String, zeroLine: Boolean = false) {
    val line = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        val min = points.minOf { it.close }
        val max = points.maxOf { it.close }
        val span = max - min
        val margin = 2.dp.toPx()
        val height = (size.height - 2 * margin).coerceAtLeast(0f)
        fun y(value: Double): Float = if (span > 0) margin + height * (1 - (value - min) / span).toFloat()
            else size.height / 2
        if (zeroLine) drawLine(baseline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
        val start = points.first().date.toEpochDay()
        val days = (points.last().date.toEpochDay() - start).coerceAtLeast(1).toDouble()
        var previous: Offset? = null
        points.forEach { point ->
            val current = Offset(((point.date.toEpochDay() - start) / days * size.width).toFloat(), y(point.close))
            previous?.let { drawLine(line, it, current, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
            previous = current
        }
    }
}
