package fin.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fin.android.data.AppState
import fin.android.valuation.AssetGain
import fin.android.valuation.GainsReport
import fin.android.valuation.PeriodGain
import fin.android.valuation.PerfMetrics
import fin.android.valuation.Position
import fin.android.valuation.ValuationLine

/** Gain green; loss reuses the theme error red (read via MaterialTheme at call sites). */
private val GainGreen = Color(0xFF1B873F)

@Composable
private fun gainColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value < 0 -> MaterialTheme.colorScheme.error
    else -> GainGreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    vm: AppViewModel,
    ready: AppState.Ready,
    onAddTx: () -> Unit,
    onSettings: () -> Unit,
    onAssetClick: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("finador") },
                actions = {
                    // The default material3 dependency here ships without the icons artifact, so the
                    // bar uses compact text actions instead of vector glyphs.
                    TextButton(onClick = { vm.syncNow() }) { Text("Sync") }
                    TextButton(onClick = { vm.refreshQuotes() }) { Text("Quotes") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                onClick = onAddTx,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Portfolio") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Gains") })
            }
            if (ready.refreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (tab) {
                0 -> PortfolioTab(ready, onAssetClick)
                else -> GainsTab(ready, onAssetClick)
            }
        }
    }
}

@Composable
private fun PortfolioTab(ready: AppState.Ready, onAssetClick: (String) -> Unit) {
    val v = ready.valuation
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SyncBanner(ready) }

        item { TotalCard(v.gross, v.tax, v.net, v.referenceCcy) }

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
            items(v.lines) { line -> LineRow(line, v.referenceCcy) }
        }

        if (v.positions.isNotEmpty()) {
            item { SectionHeader("Positions") }
            items(v.positions) { p -> PositionRow(p, v.referenceCcy, onAssetClick) }
        }

        item { Spacer(Modifier.height(72.dp)) } // clearance for the FAB
    }
}

@Composable
private fun GainsTab(ready: AppState.Ready, onAssetClick: (String) -> Unit) {
    val gains = ready.gains
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (gains == null) {
            item {
                Text(
                    "Gains are unavailable — refresh quotes to compute them.",
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
            item { AssetGainHeader() }
            items(gains.assets) { a -> AssetGainRow(a, onAssetClick) }
            item {
                Text(
                    "Per-asset gains approximate a price/FX move on the current quantity; " +
                        "intra-period buys/sells are not attributed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(72.dp)) } // clearance for the FAB
    }
}

@Composable
private fun PeriodCard(pg: PeriodGain, ccy: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pg.label, style = MaterialTheme.typography.labelMedium)
            // No "+" and a single decimal, matching the per-asset table style.
            Text(
                formatGainPercent(pg.relative),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = gainColor(pg.relative),
            )
            Text(
                "${formatGainCell(pg.absolute)} $ccy",
                style = MaterialTheme.typography.bodySmall,
                color = gainColor(pg.absolute),
            )
        }
    }
}

@Composable
private fun AssetGainHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
    }
}

@Composable
private fun AssetGainRow(a: AssetGain, onAssetClick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAssetClick(a.assetId) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            a.name,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // No currency code (implicit display ccy), no "+", one decimal; "—" for null.
        for (cell in listOf(a.d1, a.d7, a.m1)) {
            Text(
                formatGainCellOrDash(cell),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                color = gainColor(cell),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SyncBanner(ready: AppState.Ready) {
    val text = when {
        ready.sync.dirty -> "Unpushed changes (offline)"
        ready.sync.lastPull != null -> "Last pull: ${ready.sync.lastPull}"
        else -> "Not synced yet"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready.sync.dirty) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TotalCard(gross: Double, tax: Double, net: Double, ccy: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Net worth", style = MaterialTheme.typography.labelMedium)
            Text(
                formatMoney(net, ccy),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            AmountRow("Gross", formatMoney(gross, ccy))
            AmountRow("Estimated tax", formatMoney(tax, ccy))
            AmountRow("Net", formatMoney(net, ccy))
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PerfCard(perf: PerfMetrics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Performance", style = MaterialTheme.typography.labelMedium)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            AmountRow("TWR", formatPercent(perf.twr))
            AmountRow("XIRR", formatPercent(perf.xirr))
            AmountRow("CAGR", formatPercent(perf.cagr))
            AmountRow("Volatility", formatPercent(perf.volatility))
            AmountRow("Sharpe", formatRatio(perf.sharpe))
            AmountRow("Sortino", formatRatio(perf.sortino))
            AmountRow("Max drawdown", formatPercent(perf.maxDrawdown))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LineRow(line: ValuationLine, ccy: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.label, style = MaterialTheme.typography.bodyLarge)
        Text(formatMoney(line.net, ccy), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PositionRow(p: Position, ccy: String, onAssetClick: (String) -> Unit) {
    // Cash positions (null assetId) have no detail page and are not clickable.
    val clickMod = p.assetId?.let { id -> Modifier.clickable { onAssetClick(id) } } ?: Modifier
    Card(modifier = Modifier.fillMaxWidth().then(clickMod)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${p.accountName} · ${p.assetName}", style = MaterialTheme.typography.bodyMedium)
                if (p.kind == "security" && p.qty.signum() != 0) {
                    Text(
                        "${p.qty.stripTrailingZeros().toPlainString()} units",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(formatMoney(p.net, ccy), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
