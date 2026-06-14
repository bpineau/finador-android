package fin.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fin.android.valuation.AssetDetail
import fin.android.valuation.AssetPeriodGain

/** Gain green; loss reuses the theme error red (read via MaterialTheme at call sites). */
private val DetailGainGreen = Color(0xFF1B873F)

@Composable
private fun detailGainColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value < 0 -> MaterialTheme.colorScheme.error
    else -> DetailGainGreen
}

/**
 * Per-asset detail page: a simplified, price/FX-move view of one held security. Shows the holding,
 * the current value in the reference currency, a small price sparkline, and a period %/absolute
 * table. No TWR/XIRR/CAGR/Sharpe — by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(vm: AppViewModel, assetId: String, onBack: () -> Unit) {
    val detail = remember(assetId) { vm.assetDetail(assetId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "Asset", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
            Sparkline(detail)
            PeriodsCard(detail)
        }
    }
}

@Composable
private fun HeaderCard(d: AssetDetail) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                formatMoney(d.value, d.referenceCcy),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
            if (d.accounts.isNotEmpty()) DetailRow("Accounts", d.accounts.joinToString(", "))
        }
    }
}

@Composable
private fun Sparkline(d: AssetDetail) {
    val pts = d.priceHistory
    if (pts.size < 2) return // defensive: nothing meaningful to draw
    val lineColor = MaterialTheme.colorScheme.primary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Price (${d.referenceCcy})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
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
                    prev?.let { drawLine(color = lineColor, start = it, end = cur, strokeWidth = 3f) }
                    prev = cur
                }
            }
        }
    }
}

@Composable
private fun PeriodsCard(d: AssetDetail) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Performance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            PeriodHeader()
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
        Text(p.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // % column: no currency, one decimal, no "+".
        Text(
            formatGainPercent(p.relative),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = detailGainColor(p.relative),
        )
        // Absolute column: one decimal, no "+", no currency (implicit display ccy).
        Text(
            formatGainCell(p.absolute),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = detailGainColor(p.absolute),
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
            Text("—", style = MaterialTheme.typography.bodyMedium)
        } else {
            val pct = d.unrealizedPct?.let { " (${formatGainPercent(it)})" } ?: ""
            Text(
                "${formatGainCell(d.unrealized)} ${d.referenceCcy}$pct",
                style = MaterialTheme.typography.bodyMedium,
                color = detailGainColor(d.unrealized),
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
