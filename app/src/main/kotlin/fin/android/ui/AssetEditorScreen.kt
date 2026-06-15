package fin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fin.android.data.AppState
import fin.android.domain.AssetKind
import java.math.BigDecimal

private enum class KindOption(val label: String, val kind: AssetKind) {
    Security("Security", AssetKind.SECURITY),
    Property("Property", AssetKind.PROPERTY),
}

private val HUNDRED_ASSET = BigDecimal(100)

/**
 * Create (assetId == null) or edit an asset. Mirrors the desktop fields: name, kind, ticker, ISIN,
 * currency, group, comma-separated aliases, and a withholding % (shown for securities). Asset ids
 * are generated, never edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetEditorScreen(vm: AppViewModel, ready: AppState.Ready, assetId: String?, onDone: () -> Unit) {
    val existing = assetId?.let { ready.book.assets[it] }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var kindOption by remember {
        mutableStateOf(
            when (existing?.kind) {
                AssetKind.PROPERTY -> KindOption.Property
                else -> KindOption.Security
            },
        )
    }
    var ticker by rememberSaveable { mutableStateOf(existing?.ticker ?: "") }
    var isin by rememberSaveable { mutableStateOf(existing?.isin ?: "") }
    var ccy by rememberSaveable { mutableStateOf(existing?.ccy ?: "EUR") }
    var group by rememberSaveable { mutableStateOf(existing?.group ?: "") }
    var aliases by rememberSaveable { mutableStateOf(existing?.aliases?.joinToString(", ") ?: "") }
    var withholding by rememberSaveable { mutableStateOf(existing?.withholding?.let { withholdingPercent(it) } ?: "") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New asset" else "Edit asset", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = "Kind",
                options = KindOption.entries.toList(),
                selected = kindOption,
                optionLabel = { it.label },
                onSelected = { kindOption = it },
            )
            OutlinedTextField(
                value = ticker,
                onValueChange = { ticker = it },
                label = { Text("Ticker (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = isin,
                onValueChange = { isin = it.uppercase() },
                label = { Text("ISIN (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ccy,
                onValueChange = { ccy = it.uppercase() },
                label = { Text("Currency") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text("Group (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text("Aliases (comma-separated, optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (kindOption == KindOption.Security) {
                OutlinedTextField(
                    value = withholding,
                    onValueChange = { withholding = it },
                    label = { Text("Withholding % (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    error = validateAsset(name, ccy, kindOption, withholding)
                    if (error != null) return@Button
                    vm.saveAsset(
                        id = assetId,
                        kind = kindOption.kind,
                        name = name.trim(),
                        ticker = ticker.trim().ifBlank { null },
                        isin = isin.trim().ifBlank { null },
                        aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        ccy = ccy.trim(),
                        group = group.trim().ifBlank { null },
                        withholding = if (kindOption == KindOption.Security) withholdingFraction(withholding) else null,
                        onSaved = { msg ->
                            onDone()
                            vm.notifyOutcome(msg)
                        },
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Saving…" else "Save")
            }
        }
    }
}

/** A withholding fraction (0.15) as a percentage number string ("15"), trailing zeros trimmed. */
private fun withholdingPercent(fraction: Double): String =
    BigDecimal(fraction).multiply(HUNDRED_ASSET).stripTrailingZeros().toPlainString()

/** Parses the % field into a fraction (15 → 0.15), or null when blank. */
private fun withholdingFraction(pct: String): Double? {
    val t = pct.trim().removeSuffix("%").trim()
    if (t.isBlank()) return null
    return BigDecimal(t).divide(HUNDRED_ASSET).toDouble()
}

private fun validateAsset(name: String, ccy: String, kind: KindOption, withholding: String): String? {
    if (name.isBlank()) return "Name required"
    if (ccy.isBlank()) return "Currency required"
    if (kind == KindOption.Security && withholding.isNotBlank()) {
        val r = runCatching { BigDecimal(withholding.trim().removeSuffix("%").trim()) }.getOrNull()
            ?: return "Withholding must be a number"
        if (r.signum() < 0 || r > HUNDRED_ASSET) return "Withholding must be between 0 and 100"
    }
    return null
}
