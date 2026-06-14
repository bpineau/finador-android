package fin.android.data

import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.TxKind
import fin.android.format.Ledger
import fin.android.market.CacheSidecar
import fin.android.market.Quotes
import fin.android.remote.GithubConfig
import fin.android.remote.RemoteConfig
import fin.android.remote.RemoteError
import fin.android.remote.SyncOutcome
import fin.android.remote.SyncState
import fin.android.valuation.Valuator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The app's single facade over the engine: resolves onboarding/unlock state, opens the ledger via
 * [Sync], values it, and applies transactions. All blocking work runs on [Dispatchers.IO]; UI
 * observes [state]. The passphrase lives in memory only for the unlocked session.
 */
class AppRepository(private val container: AppContainer) {
    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var passphrase: String? = null
    private var ledger: Ledger? = null
    private var market: MarketData = MarketData()

    /** Decides whether onboarding is needed or we can offer biometric unlock. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val cfg = container.loadConfig()
        val ready = cfg.isGithub && container.secretStore.getPat() != null && container.secretStore.hasPassphrase()
        _state.value = if (ready) AppState.Locked else AppState.Onboarding
    }

    suspend fun onboard(
        owner: String, repo: String, path: String, branch: String, token: String, pass: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = RemoteConfig(
                source = "github",
                github = GithubConfig(owner.trim(), repo.trim(), path.trim().ifBlank { "portfolio.fin" }, branch.trim().ifBlank { "main" }),
                readPullAfter = "1h",
            )
            container.saveConfig(cfg)
            container.secretStore.putPat(token.trim())
            container.secretStore.putPassphrase(pass)

            val sync = container.buildSync(cfg, token.trim())
            try { sync.pullIfStale() } catch (e: RemoteError.Offline) { /* offline first run — create locally */ }

            val wc = container.workingCopy(cfg)
            if (!wc.exists()) {
                wc.parentFile?.mkdirs()
                wc.writeBytes(Ledger.create(pass).toBytes())
                try { sync.sync(pass) } catch (e: RemoteError) { /* offline/missing — keep local, push later */ }
            }
            unlockInternal(cfg, pass)
        }
    }

    /** Call after a successful BiometricPrompt. Uses the stored passphrase. */
    suspend fun unlock(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = container.loadConfig()
            val pass = container.secretStore.getPassphrase() ?: error("no stored passphrase")
            unlockInternal(cfg, pass)
        }
    }

    suspend fun refreshQuotes() = withContext(Dispatchers.IO) {
        val l = ledger ?: return@withContext
        runCatching {
            val updated = Quotes.refresh(l.book, market, from = LocalDate.now().minusYears(2), now = LocalDate.now())
            market = updated
            CacheSidecar.write(container.marketCacheFile(l.fileId), l.cacheKey, updated)
        }
        emitReady(currentSyncState(), message = null, refreshing = false)
    }

    suspend fun addTransaction(
        date: LocalDate, accountId: String, assetId: String?, kind: TxKind,
        qty: BigDecimal, amount: BigDecimal, ccy: String, note: String?,
    ): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return@withContext Result.failure(IllegalStateException("no token"))
        val pass = passphrase ?: return@withContext Result.failure(IllegalStateException("locked"))
        runCatching {
            val sync = container.buildSync(cfg, token)
            val outcome = sync.mutate(pass, "finador-android: add ${kind.name}") { ledgerSnapshot ->
                ledgerSnapshot.addTransaction(date, accountId, assetId, kind, qty, Money(amount, ccy), note?.ifBlank { null })
            }
            ledger = Ledger.open(container.workingCopy(cfg).readBytes(), pass)
            emitReady(sync.state(), outcome.message, refreshing = false)
            outcome
        }
    }

    suspend fun syncNow(): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return@withContext Result.failure(IllegalStateException("no token"))
        val pass = passphrase ?: return@withContext Result.failure(IllegalStateException("locked"))
        runCatching {
            val sync = container.buildSync(cfg, token)
            val outcome = sync.sync(pass)
            ledger = Ledger.open(container.workingCopy(cfg).readBytes(), pass)
            emitReady(sync.state(), outcome.message, refreshing = false)
            outcome
        }
    }

    suspend fun forget() = withContext(Dispatchers.IO) {
        container.secretStore.purge()
        container.clearConfig()
        passphrase = null
        ledger = null
        market = MarketData()
        _state.value = AppState.Onboarding
    }

    fun currentBook(): Book? = ledger?.book

    private fun unlockInternal(cfg: RemoteConfig, pass: String) {
        val token = container.secretStore.getPat() ?: error("no token")
        val sync = container.buildSync(cfg, token)
        val opened = sync.openForRead(pass)
        passphrase = pass
        ledger = opened
        market = CacheSidecar.read(container.marketCacheFile(opened.fileId), opened.cacheKey) ?: MarketData()
        emitReady(sync.state(), message = null, refreshing = true) // quotes refreshed asynchronously next
    }

    private fun currentSyncState(): SyncState {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return SyncState()
        return container.buildSync(cfg, token).state()
    }

    private fun emitReady(syncState: SyncState, message: String?, refreshing: Boolean) {
        val l = ledger ?: return
        val valuation = Valuator.value(l.book, market, referenceCcy = null, at = LocalDate.now(), byGroup = true)
        _state.value = AppState.Ready(valuation, l.book, syncState, message, refreshing)
    }
}
