package fin.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import fin.android.App
import fin.android.data.AppContainer
import fin.android.data.AppRepository
import fin.android.domain.AssetKind
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

/** Summary of the configured remote, for the Settings screen. */
data class RepoSummary(val owner: String, val repo: String, val path: String, val branch: String)

/**
 * Drives the repository for the UI. Collects [state] in composables via
 * collectAsStateWithLifecycle; surfaces transient failures/messages through [message].
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val repo: AppRepository = container.repository

    val state: StateFlow<fin.android.data.AppState> = repo.state

    private val _message = MutableStateFlow<String?>(null)
    /** Transient text for a snackbar (errors and outcome messages). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a long operation (onboard/unlock/save) runs, so screens can show a spinner. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _onboardError = MutableStateFlow<String?>(null)
    /** Persistent inline error for the onboarding/unlock screens - NOT cleared by the snackbar. */
    val onboardError: StateFlow<String?> = _onboardError.asStateFlow()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch { repo.refresh() }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Surface a SyncOutcome/info message in the snackbar (e.g. after saving a transaction). */
    fun notifyOutcome(text: String) {
        notify(text)
    }

    private fun notify(text: String?) {
        if (!text.isNullOrBlank()) _message.value = text
    }

    private fun fail(e: Throwable) {
        android.util.Log.w("finador", "operation failed", e)
        _message.value = e.message ?: e.javaClass.simpleName
    }

    /** Launches [block] with the busy flag set for its duration (cleared on success or failure). */
    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    fun onboard(owner: String, repo: String, path: String, branch: String, token: String, pass: String) = launchBusy {
        _onboardError.value = null
        this@AppViewModel.repo.onboard(owner, repo, path, branch, token, pass)
            .onSuccess { refreshQuotes() }
            .onFailure { failOnboard(it) }
    }

    fun unlock() = launchBusy {
        _onboardError.value = null
        repo.unlock()
            .onSuccess { refreshQuotes() }
            .onFailure { failOnboard(it) }
    }

    /** Onboarding/unlock failures go to the persistent inline channel (not the transient snackbar). */
    private fun failOnboard(e: Throwable) {
        android.util.Log.w("finador", "onboard/unlock failed", e)
        _onboardError.value = e.message ?: e.javaClass.simpleName
    }

    fun refreshQuotes() {
        viewModelScope.launch { repo.refreshQuotes() }
    }

    fun syncNow() {
        viewModelScope.launch {
            repo.syncNow()
                .onSuccess { notify(it.message) }
                .onFailure { fail(it) }
        }
    }

    fun addTransaction(
        date: LocalDate,
        accountId: String,
        assetId: String?,
        kind: TxKind,
        qty: BigDecimal,
        amount: BigDecimal,
        ccy: String,
        note: String?,
        onSaved: (String) -> Unit,
    ) = launchBusy {
        repo.addTransaction(date, accountId, assetId, kind, qty, amount, ccy, note)
            .onSuccess { onSaved(it.message) }
            .onFailure { fail(it) }
    }

    /** Creates (id == null) or edits an account, then runs [onSaved] with the outcome message. */
    fun saveAccount(
        id: String?,
        name: String,
        ccy: String,
        tax: TaxRule,
        aliases: List<String>,
        onSaved: (String) -> Unit,
    ) = launchBusy {
        val result = if (id == null) {
            repo.addAccount(name, ccy, tax, aliases)
        } else {
            repo.editAccount(id, name, ccy, tax, aliases)
        }
        result.onSuccess { onSaved(it.message) }.onFailure { fail(it) }
    }

    /** Deletes an account; refused with a snackbar message if a transaction still references it. */
    fun deleteAccount(id: String) = launchBusy {
        repo.deleteAccount(id)
            .onSuccess { notify(it.message) }
            .onFailure { fail(it) }
    }

    /** Creates (id == null) or edits an asset, then runs [onSaved] with the outcome message. */
    fun saveAsset(
        id: String?,
        kind: AssetKind,
        name: String,
        ticker: String?,
        isin: String?,
        aliases: List<String>,
        ccy: String,
        group: String?,
        withholding: Double?,
        onSaved: (String) -> Unit,
    ) = launchBusy {
        val result = if (id == null) {
            repo.addAsset(kind, name, ticker, isin, aliases, ccy, group, withholding)
        } else {
            repo.editAsset(id, kind, name, ticker, isin, aliases, ccy, group, withholding)
        }
        result.onSuccess { onSaved(it.message) }.onFailure { fail(it) }
    }

    /** Deletes an asset; refused with a snackbar message if a transaction still references it. */
    fun deleteAsset(id: String) = launchBusy {
        repo.deleteAsset(id)
            .onSuccess { notify(it.message) }
            .onFailure { fail(it) }
    }

    fun forget() {
        viewModelScope.launch { repo.forget() }
    }

    /** Persists the display currency and refreshes quotes (so its FX series is fetched). */
    fun setDisplayCurrency(code: String) {
        viewModelScope.launch { repo.setDisplayCurrency(code) }
    }

    /** The effective display currency (override → book's currency → EUR), for the Settings dropdown. */
    fun displayCurrency(): String =
        runCatching { container.loadConfig().displayCurrency }.getOrNull()
            ?: (state.value as? fin.android.data.AppState.Ready)?.book?.config?.get("currency")
            ?: "EUR"

    /**
     * The per-asset detail for the detail screen. Reads the copy precomputed in [AppState.Ready]
     * (instant); only falls back to an on-demand computation if it's somehow absent.
     */
    fun assetDetail(assetId: String): fin.android.valuation.AssetDetail? =
        (state.value as? fin.android.data.AppState.Ready)?.assetDetails?.get(assetId) ?: repo.assetDetail(assetId)

    /** Reads the persisted remote config for the Settings screen (no secrets). */
    fun repoSummary(): RepoSummary? {
        val cfg = runCatching { container.loadConfig() }.getOrNull() ?: return null
        val gh = cfg.github ?: return null
        return RepoSummary(gh.owner, gh.repo, gh.path, gh.branch)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(app.container) as T
            }
        }
    }
}
