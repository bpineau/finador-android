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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fin.android.data.AppState
import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.TxKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val cashKinds = setOf(TxKind.deposit, TxKind.withdraw)
private val needsAsset = setOf(TxKind.buy, TxKind.sell, TxKind.dividend)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxEntryScreen(vm: AppViewModel, ready: AppState.Ready, onDone: () -> Unit) {
    val book = ready.book
    val accounts = remember(book) { book.accounts.values.toList() }
    val assets = remember(book) { book.assets.values.toList() }

    var account by remember { mutableStateOf(accounts.firstOrNull()) }
    var kind by remember { mutableStateOf(TxKind.buy) }
    var asset by remember { mutableStateOf<Asset?>(null) }
    // Text fields survive config changes (rotation) so an in-progress entry isn't lost.
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var qty by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var ccy by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.ccy ?: "EUR") }
    var ccyTouched by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle() // resets on success AND failure
    var error by remember { mutableStateOf<String?>(null) }

    val isCash = kind in cashKinds
    val showAsset = !isCash

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add transaction", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DropdownField(
                label = "Account",
                options = accounts,
                selected = account,
                optionLabel = { it.name },
                onSelected = {
                    account = it
                    if (!ccyTouched) ccy = it.ccy // don't clobber a currency the user typed
                },
            )

            DropdownField(
                label = "Kind",
                options = TxKind.entries.toList(),
                selected = kind,
                optionLabel = { it.name },
                onSelected = { kind = it },
            )

            if (showAsset) {
                DropdownField(
                    label = if (kind in needsAsset) "Asset" else "Asset (optional)",
                    options = assets,
                    selected = asset,
                    optionLabel = { it.name },
                    onSelected = { asset = it },
                    placeholder = "Select asset",
                )
            }

            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Date (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind == TxKind.buy || kind == TxKind.sell) {
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = ccy,
                onValueChange = { ccy = it.uppercase(); ccyTouched = true },
                label = { Text("Currency") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    error = validate(account, kind, asset, dateText, qty, amount, ccy)
                    if (error != null) return@Button
                    val acc = account!!
                    val parsedDate = LocalDate.parse(dateText.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                    val parsedQty = if (kind == TxKind.buy || kind == TxKind.sell) {
                        BigDecimal(qty.trim())
                    } else {
                        BigDecimal.ZERO
                    }
                    val parsedAmount = BigDecimal(amount.trim())
                    val assetId = if (isCash) null else asset?.id
                    vm.addTransaction(
                        date = parsedDate,
                        accountId = acc.id,
                        assetId = assetId,
                        kind = kind,
                        qty = parsedQty,
                        amount = parsedAmount,
                        ccy = ccy.trim().ifBlank { acc.ccy },
                        note = note.trim(),
                        onSaved = { msg ->
                            // Return to overview, then surface the SyncOutcome message in a snackbar.
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

private fun validate(
    account: Account?,
    kind: TxKind,
    asset: Asset?,
    dateText: String,
    qty: String,
    amount: String,
    ccy: String,
): String? {
    if (account == null) return "Pick an account"
    if (kind in needsAsset && asset == null) return "Pick an asset for ${kind.name}"
    runCatching { LocalDate.parse(dateText.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }
        .getOrElse { return "Date must be yyyy-MM-dd" }
    if (kind == TxKind.buy || kind == TxKind.sell) {
        if (qty.toBigDecimalOrNull() == null) return "Quantity must be a number"
    }
    if (amount.toBigDecimalOrNull() == null) return "Amount must be a number"
    if (ccy.isBlank()) return "Currency required"
    return null
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this.trim()) }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    onClick = {
                        onSelected(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}
