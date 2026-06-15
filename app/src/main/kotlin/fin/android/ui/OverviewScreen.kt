package fin.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fin.android.data.AppState
import fin.android.valuation.AssetGain
import fin.android.valuation.PeriodGain
import fin.android.valuation.PerfMetrics
import fin.android.valuation.Position
import fin.android.valuation.ValuationLine


private val ListPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)

/* ---------------------------------------------------------------------------------------------- */
/* Portfolio section                                                                              */
/* ---------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(vm: AppViewModel, ready: AppState.Ready, onAssetClick: (String) -> Unit) {
    Scaffold(topBar = { FinTopBar("finador", ready.refreshing) { vm.syncNow(); vm.refreshQuotes() } }) { padding ->
        val v = ready.valuation
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SyncBanner(ready) }

            item { TotalCard(v.gross, v.tax, v.net, v.referenceCcy, ready.perf) }

            if (v.taxNote != null) {
                item {
                    Text(
                        v.taxNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ready.perf != null) {
                item { PerfCard(ready.perf) }
            }

            if (v.lines.isNotEmpty()) {
                item { SectionHeader("Breakdown") }
                item { CardList(v.lines) { line -> LineRow(line, v.referenceCcy) } }
            }

            if (v.positions.isNotEmpty()) {
                item { SectionHeader("Positions") }
                item {
                    CardList(v.positions) { p ->
                        PositionRow(p, v.referenceCcy, onAssetClick)
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Gains section                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GainsScreen(vm: AppViewModel, ready: AppState.Ready, onAssetClick: (String) -> Unit) {
    Scaffold(topBar = { FinTopBar("Gains", ready.refreshing) { vm.syncNow(); vm.refreshQuotes() } }) { padding ->
        val gains = ready.gains
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (gains == null) {
                item {
                    Text(
                        "Gains are unavailable - refresh quotes to compute them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            item { SectionHeader("Portfolio gains") }
            // Two-column grid of compact period cards.
            items(gains.periods.chunked(2)) { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (pg in pair) {
                        PeriodCard(pg, gains.referenceCcy, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (gains.assets.isNotEmpty()) {
                item { SectionHeader("Per-asset gains") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column {
                            AssetGainHeader()
                            gains.assets.forEachIndexed { i, a ->
                                if (i > 0) HairlineDivider()
                                AssetGainRow(a, onAssetClick)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Per-asset gains approximate a price/FX move on the current quantity; " +
                            "intra-period buys/sells are not attributed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Shared chrome                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

/** Title + a single refresh icon that triggers sync + quotes; shows a spinner while refreshing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinTopBar(title: String, refreshing: Boolean, onRefresh: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        actions = {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp).padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh sync & quotes")
                }
            }
        },
    )
}

/** Wraps a list of native rows in a single rounded surfaceVariant card with hairline separators. */
@Composable
private fun <T> CardList(items: List<T>, row: @Composable (T) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column {
            items.forEachIndexed { i, item ->
                if (i > 0) HairlineDivider()
                row(item)
            }
        }
    }
}

@Composable
private fun HairlineDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/* ---------------------------------------------------------------------------------------------- */
/* Gains pieces                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun PeriodCard(pg: PeriodGain, ccy: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pg.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatSignedPercent(pg.relative),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = gainLossColor(pg.relative),
            )
            Text(
                "${formatGainCell(pg.absolute)} $ccy",
                style = MaterialTheme.typography.bodySmall,
                color = gainLossColor(pg.absolute),
            )
        }
    }
}

@Composable
private fun AssetGainHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            "Asset",
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (h in listOf("1d", "7d", "1m")) {
            Text(
                h,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun AssetGainRow(a: AssetGain, onAssetClick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAssetClick(a.assetId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            a.name,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        for (cell in listOf(a.d1, a.d7, a.m1)) {
            Text(
                formatGainCellOrDash(cell),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium,
                color = gainLossColor(cell),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(4.dp))
        Chevron()
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Portfolio pieces                                                                               */
/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun SyncBanner(ready: AppState.Ready) {
    if (!ready.sync.dirty) return // a clean state needs no banner; quiet by default.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Unpushed changes (offline)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The hero card: large bold net-worth number + a compact gross / tax / net breakdown. */
@Composable
private fun TotalCard(gross: Double, tax: Double, net: Double, ccy: String, perf: PerfMetrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Net worth",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMoney(net, ccy),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            AmountRow("Gross", formatMoney(gross, ccy))
            AmountRow("Estimated tax", formatMoney(tax, ccy))
            AmountRow("Net", formatMoney(net, ccy))
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PerfCard(perf: PerfMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Performance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
            PerfRow("TWR", formatPercent(perf.twr), perf.twr)
            PerfRow("XIRR", formatPercent(perf.xirr), perf.xirr)
            PerfRow("CAGR", formatPercent(perf.cagr), perf.cagr)
            AmountRow("Volatility", formatPercent(perf.volatility))
            AmountRow("Sharpe", formatRatio(perf.sharpe))
            AmountRow("Sortino", formatRatio(perf.sortino))
            PerfRow("Max drawdown", formatPercent(perf.maxDrawdown), perf.maxDrawdown)
        }
    }
}

/** A perf row whose value is tinted gain/loss by its sign. */
@Composable
private fun PerfRow(label: String, value: String, raw: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = gainLossColor(raw))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun LineRow(line: ValuationLine, ccy: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(formatMoney(line.net, ccy), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Native list row: name + value, a trailing chevron, whole-row ripple (when navigable). */
@Composable
private fun PositionRow(p: Position, ccy: String, onAssetClick: (String) -> Unit) {
    // Cash positions (null assetId) have no detail page and are not clickable.
    val clickMod = p.assetId?.let { id -> Modifier.clickable { onAssetClick(id) } } ?: Modifier
    Row(
        Modifier.fillMaxWidth().then(clickMod).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${p.accountName} · ${p.assetName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (p.kind == "security" && p.qty.signum() != 0) {
                Text(
                    formatQuantity(p.qty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            formatMoney(p.net, ccy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (p.assetId != null) {
            Spacer(Modifier.size(4.dp))
            Chevron()
        } else {
            Spacer(Modifier.size(20.dp))
        }
    }
}

/** Subtle trailing chevron that signals a navigable native row. */
@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
