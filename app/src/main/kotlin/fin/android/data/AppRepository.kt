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
import fin.android.valuation.Gains
import fin.android.valuation.Perf
import fin.android.valuation.PerfMetrics
import fin.android.valuation.Valuator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Serializes every mutation of the shared ledger/market/config (the UI can launch e.g. sync and
    // a quote refresh concurrently). Not reentrant — a locked method must call the *Locked helpers,
    // never another public (locked) method.
    private val mutex = Mutex()
    private suspend fun <T> exclusive(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** Decides whether onboarding is needed or we can offer biometric unlock. */
    suspend fun refresh() = exclusive {
        val cfg = container.loadConfig()
        val ready = cfg.isGithub && container.secretStore.getPat() != null && container.secretStore.hasPassphrase()
        _state.value = if (ready) AppState.Locked else AppState.Onboarding
    }

    suspend fun onboard(
        owner: String, repo: String, path: String, branch: String, token: String, pass: String,
    ): Result<Unit> = exclusive {
        val cfg = RemoteConfig(
            source = "github",
            github = GithubConfig(owner.trim(), repo.trim(), path.trim().ifBlank { "portfolio.fin" }, branch.trim().ifBlank { "main" }),
            readPullAfter = "1h",
        )
        runCatching {
            container.saveConfig(cfg)
            container.secretStore.putPat(token.trim())
            container.secretStore.putPassphrase(pass)

            val sync = container.buildSync(cfg, token.trim())
            try { sync.pullIfStale() } catch (e: RemoteError.Offline) { /* offline first run — create locally */ }

            val wc = container.workingCopy(cfg)
            if (!wc.exists()) {
                wc.parentFile?.mkdirs()
                wc.writeBytes(Ledger.create(pass).toBytes())
                sync.sync(pass) // first push; auth/permission errors surface here, not swallowed
            }
            unlockInternal(cfg, pass)
        }.onFailure {
            // A failed first connect must not strand the user on a Locked screen with saved-but-bad
            // settings: roll back everything so the app returns to the editable onboarding form.
            container.workingCopy(cfg).delete()
            container.clearConfig()
            container.secretStore.purge()
            passphrase = null
            ledger = null
        }
    }

    /** Call after a successful BiometricPrompt. Uses the stored passphrase. */
    suspend fun unlock(): Result<Unit> = exclusive {
        runCatching {
            val cfg = container.loadConfig()
            val pass = container.secretStore.getPassphrase() ?: error("no stored passphrase")
            unlockInternal(cfg, pass)
        }
    }

    suspend fun refreshQuotes() = exclusive { refreshQuotesLocked() }

    /** Quote refresh without taking the lock — call only from an already-locked method. */
    private suspend fun refreshQuotesLocked() {
        val l = ledger ?: return
        runCatching {
            val ref = container.loadConfig().displayCurrency
            val updated = Quotes.refresh(
                l.book, market, from = LocalDate.now().minusYears(2), now = LocalDate.now(),
                referenceCcy = ref,
            )
            market = updated
            CacheSidecar.write(container.marketCacheFile(l.fileId), l.cacheKey, updated)
        }
        emitReady(currentSyncState(), message = null, refreshing = false)
    }

    /**
     * Persists [ccy] as the display currency every value/gain is shown in, then refreshes quotes so
     * the new currency's FX series is fetched, and re-emits. A blank value clears the override.
     */
    suspend fun setDisplayCurrency(ccy: String) = exclusive {
        val cfg = container.loadConfig()
        container.saveConfig(cfg.copy(displayCurrency = ccy.trim().uppercase().ifBlank { null }))
        refreshQuotesLocked() // already holding the lock
    }

    fun assetDetail(assetId: String): fin.android.valuation.AssetDetail? =
        ledger?.let { Gains.assetDetail(it.book, market, container.loadConfig().displayCurrency, LocalDate.now(), assetId) }

    suspend fun addTransaction(
        date: LocalDate, accountId: String, assetId: String?, kind: TxKind,
        qty: BigDecimal, amount: BigDecimal, ccy: String, note: String?,
    ): Result<SyncOutcome> = exclusive {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return@exclusive Result.failure(IllegalStateException("no token"))
        val pass = passphrase ?: return@exclusive Result.failure(IllegalStateException("locked"))
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

    suspend fun syncNow(): Result<SyncOutcome> = exclusive {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return@exclusive Result.failure(IllegalStateException("no token"))
        val pass = passphrase ?: return@exclusive Result.failure(IllegalStateException("locked"))
        runCatching {
            val sync = container.buildSync(cfg, token)
            val outcome = sync.sync(pass)
            ledger = Ledger.open(container.workingCopy(cfg).readBytes(), pass)
            emitReady(sync.state(), outcome.message, refreshing = false)
            outcome
        }
    }

    suspend fun forget() = exclusive {
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
        val today = LocalDate.now()
        val ref = container.loadConfig().displayCurrency // null → engine falls back to book/EUR
        val valuation = Valuator.value(l.book, market, referenceCcy = ref, at = today, byGroup = true)
        val perf = computePerf(l.book, today, ref)
        // Gains are a pure read over the same engine; never let them crash the UI.
        val gains = runCatching { Gains.report(l.book, market, referenceCcy = ref, today = today) }.getOrNull()
        // Precompute every held-security detail now (reusing the valuation's positions, so no extra
        // book folds) — opening an asset's page is then an instant map lookup, not a computation.
        val assetDetails = runCatching {
            l.book.assets.values
                .mapNotNull { a -> Gains.assetDetail(l.book, market, ref, today, a.id, valuation.positions)?.let { a.id to it } }
                .toMap()
        }.getOrDefault(emptyMap())
        _state.value = AppState.Ready(valuation, perf, gains, l.book, syncState, message, refreshing, assetDetails)
    }

    /**
     * Performance over the full available period (from the earliest tx, or one
     * year back if none) to today, in [ref] (null falls back to the book's currency).
     * Computed synchronously alongside the valuation; any failure or undefined result
     * yields null rather than crashing the UI.
     */
    private fun computePerf(book: Book, today: LocalDate, ref: String?): PerfMetrics? = runCatching {
        val earliest = book.txs.values.minByOrNull { it.date }?.date
        val from = earliest ?: today.minusYears(1)
        if (!from.isBefore(today)) return@runCatching null
        Perf.metrics(book, market, referenceCcy = ref, from = from, to = today)
    }.getOrNull()
}
