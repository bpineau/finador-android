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
import fin.android.domain.TaxRule
import fin.android.domain.ratePercent
import java.math.BigDecimal

private enum class TaxMode(val label: String) { None("No tax"), Gains("On gains"), Value("On full value") }

private val HUNDRED = BigDecimal(100)

/**
 * Create (accountId == null) or edit an account. Mirrors the desktop fields: name, currency,
 * tax mode + rate, and comma-separated aliases. Account ids are generated, never edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorScreen(vm: AppViewModel, ready: AppState.Ready, accountId: String?, onDone: () -> Unit) {
    val existing = accountId?.let { ready.book.accounts[it] }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var ccy by rememberSaveable { mutableStateOf(existing?.ccy ?: "EUR") }
    var taxMode by remember {
        mutableStateOf(
            when (existing?.tax) {
                is TaxRule.Gains -> TaxMode.Gains
                is TaxRule.Value -> TaxMode.Value
                else -> TaxMode.None
            },
        )
    }
    var rate by rememberSaveable {
        mutableStateOf(
            when (val t = existing?.tax) {
                is TaxRule.Gains -> ratePercent(t.rate)
                is TaxRule.Value -> ratePercent(t.rate)
                else -> ""
            },
        )
    }
    var aliases by rememberSaveable { mutableStateOf(existing?.aliases?.joinToString(", ") ?: "") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New account" else "Edit account", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(FinIcons.Close, contentDescription = "Cancel") }
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
            OutlinedTextField(
                value = ccy,
                onValueChange = { ccy = it.uppercase() },
                label = { Text("Currency") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = "Tax",
                options = TaxMode.entries.toList(),
                selected = taxMode,
                optionLabel = { it.label },
                onSelected = { taxMode = it },
            )
            if (taxMode != TaxMode.None) {
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("Tax rate %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text("Aliases (comma-separated, optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    error = validateAccount(name, ccy, taxMode, rate)
                    if (error != null) return@Button
                    val tax = when (taxMode) {
                        TaxMode.None -> TaxRule.None
                        TaxMode.Gains -> TaxRule.Gains(rateFraction(rate))
                        TaxMode.Value -> TaxRule.Value(rateFraction(rate))
                    }
                    vm.saveAccount(
                        id = accountId,
                        name = name.trim(),
                        ccy = ccy.trim(),
                        tax = tax,
                        aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() },
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

private fun rateFraction(rate: String): BigDecimal =
    BigDecimal(rate.trim().removeSuffix("%").trim()).divide(HUNDRED)

private fun validateAccount(name: String, ccy: String, taxMode: TaxMode, rate: String): String? {
    if (name.isBlank()) return "Name required"
    if (ccy.isBlank()) return "Currency required"
    if (taxMode != TaxMode.None) {
        val r = runCatching { BigDecimal(rate.trim().removeSuffix("%").trim()) }.getOrNull()
            ?: return "Tax rate must be a number"
        if (r.signum() < 0 || r > HUNDRED) return "Tax rate must be between 0 and 100"
    }
    return null
}
