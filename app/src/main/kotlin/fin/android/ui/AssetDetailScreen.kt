package fin.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fin.android.valuation.AssetDetail
import fin.android.valuation.AssetPeriodGain

/**
 * Per-asset detail page: a simplified, price/FX-move view of one held security. Shows the holding,
 * the current value in the reference currency, a small price sparkline, and a period %/absolute
 * table. Pushed full-screen over the bottom bar with an icon back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(vm: AppViewModel, assetId: String, onBack: () -> Unit) {
    val detail = remember(assetId) { vm.assetDetail(assetId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.name ?: "Asset",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detail == null) {
                Text(
                    "This asset is no longer held, or could not be priced.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            HeaderCard(detail)
            if (detail.kind == "property") {
                if (detail.valuations.isNotEmpty()) ValuationsCard(detail)
            } else {
                Sparkline(detail)
                PeriodsCard(detail)
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun HeaderCard(d: AssetDetail) {
    DetailCard {
        Text(
            formatMoney(d.value, d.referenceCcy),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (d.kind == "property") {
            d.isin?.let { DetailRow("ISIN", it) }
            DetailRow(
                "Purchase / initial value",
                if (d.costBasis != null) formatMoney(d.costBasis, d.referenceCcy) else "—",
            )
            DetailRow("Current value", formatMoney(d.value, d.referenceCcy))
            UnrealizedRow(d)
            d.taxRule?.let { DetailRow("Tax", it) }
            if (d.accounts.isNotEmpty()) DetailRow("Accounts", d.accounts.joinToString(", "))
        } else {
            d.ticker?.let { DetailRow("Ticker", it) }
            d.isin?.let { DetailRow("ISIN", it) }
            DetailRow("Quantity", "${d.qty.stripTrailingZeros().toPlainString()} units")
            DetailRow(
                "Market price",
                if (d.price != null) "${formatAmount(d.price)} ${d.assetCcy}" else "—",
            )
            DetailRow(
                "Avg buy price",
                if (d.avgBuyPrice != null) formatMoney(d.avgBuyPrice, d.referenceCcy) else "—",
            )
            DetailRow("Value", formatMoney(d.value, d.referenceCcy))
            DetailRow(
                "Cost basis",
                if (d.costBasis != null) formatMoney(d.costBasis, d.referenceCcy) else "—",
            )
            UnrealizedRow(d)
            d.taxRule?.let { DetailRow("Tax", it) }
            if (d.accounts.isNotEmpty()) DetailRow("Accounts", d.accounts.joinToString(", "))
        }
    }
}

/** Dated declared values (statement history) — shown for property. */
@Composable
private fun ValuationsCard(d: AssetDetail) {
    DetailCard {
        Text(
            "Valuations",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        d.valuations.sortedByDescending { it.date }.forEach { v ->
            DetailRow(v.date.toString(), "${formatAmount(v.amount)} ${v.ccy}")
        }
    }
}

/** Time ranges offered above the price sparkline; cutoff is relative to the latest point. */
private enum class ChartRange(val label: String) {
    M1("1m"), M6("6m"), Y1("1y"), MAX("Max");

    fun cutoff(anchor: LocalDate): LocalDate? = when (this) {
        M1 -> anchor.minusMonths(1)
        M6 -> anchor.minusMonths(6)
        Y1 -> anchor.minusYears(1)
        MAX -> null
    }
}

/** Discreet inline range picker: small accent pills, the selected one in a filled accent chip. */
@Composable
private fun RangeChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ChartRange.entries.forEachIndexed { i, r ->
            val isSelected = i == selected
            Text(
                r.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Sparkline(d: AssetDetail) {
    val all = d.priceHistory
    if (all.size < 2) return // defensive: nothing meaningful to draw
    val lineColor = MaterialTheme.colorScheme.primary

    var rangeOrdinal by rememberSaveable { mutableStateOf(ChartRange.M6.ordinal) }
    val range = ChartRange.entries[rangeOrdinal]
    val anchor = all.last().date
    val pts = remember(rangeOrdinal, all) {
        val cutoff = range.cutoff(anchor)
        val filtered = if (cutoff == null) all else all.filter { !it.date.isBefore(cutoff) }
        if (filtered.size >= 2) filtered else all // fall back to the full series if a range is too short
    }

    DetailCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Price (${d.referenceCcy})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RangeChips(rangeOrdinal) { rangeOrdinal = it }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val closes = pts.map { it.close }
            val min = closes.min()
            val max = closes.max()
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
            val w = size.width
            val h = size.height
            val dx = if (pts.size > 1) w / (pts.size - 1) else 0f
            var prev: Offset? = null
            pts.forEachIndexed { i, p ->
                val x = dx * i
                val y = (h - ((p.close - min) / span * h)).toFloat()
                val cur = Offset(x, y)
                prev?.let { drawLine(color = lineColor, start = it, end = cur, strokeWidth = 4f, cap = StrokeCap.Round) }
                prev = cur
            }
        }
        Text(
            "${pts.first().date} → ${pts.last().date} · ${pts.size} pts",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeriodsCard(d: AssetDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Performance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            PeriodHeader()
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            for (p in d.periods) PeriodRow(p)
        }
    }
}

@Composable
private fun PeriodHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Period",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "%",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "abs",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeriodRow(p: AssetPeriodGain) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            p.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            formatGainPercent(p.relative),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = gainLossColor(p.relative),
        )
        Text(
            formatGainCell(p.absolute),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = gainLossColor(p.absolute),
        )
    }
}

/** Unrealized +/− value (current value − cost basis), coloured, with its percentage. */
@Composable
private fun UnrealizedRow(d: AssetDetail) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "+/− value",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (d.unrealized == null) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        } else {
            val pct = d.unrealizedPct?.let { " (${formatGainPercent(it)})" } ?: ""
            Text(
                "${formatGainCell(d.unrealized)} ${d.referenceCcy}$pct",
                style = MaterialTheme.typography.bodyMedium,
                color = gainLossColor(d.unrealized),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
