package fin.android.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fin.android.data.AppState
import fin.android.valuation.Position
import fin.android.valuation.ValuationLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    vm: AppViewModel,
    ready: AppState.Ready,
    onAddTx: () -> Unit,
    onSettings: () -> Unit,
) {
    val v = ready.valuation
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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ready.refreshing) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

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

            if (v.lines.isNotEmpty()) {
                item { SectionHeader("Breakdown") }
                items(v.lines) { line -> LineRow(line, v.referenceCcy) }
            }

            if (v.positions.isNotEmpty()) {
                item { SectionHeader("Positions") }
                items(v.positions) { p -> PositionRow(p, v.referenceCcy) }
            }

            item { Spacer(Modifier.height(72.dp)) } // clearance for the FAB
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
private fun PositionRow(p: Position, ccy: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
