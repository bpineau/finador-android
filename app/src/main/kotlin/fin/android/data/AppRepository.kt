package fin.android.data

import fin.android.crypto.Ids
import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import fin.android.format.Ledger
import fin.android.market.Airfund
import fin.android.market.CacheSidecar
import fin.android.market.Ft
import fin.android.market.FxRates
import fin.android.market.Morningstar
import fin.android.market.MultiSource
import fin.android.market.Quotes
import fin.android.market.Yahoo
import fin.android.remote.GithubConfig
import fin.android.remote.RemoteConfig
import fin.android.remote.RemoteError
import fin.android.remote.Sync
import fin.android.remote.SyncOutcome
import fin.android.remote.SyncState
import fin.android.storage.AtomicFile
import fin.android.valuation.AssetDetail
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
import java.time.Clock
import java.time.LocalDate

/**
 * The app's single facade over the engine: resolves onboarding/unlock state, opens the ledger via
 * [Sync], values it, and applies transactions. All blocking work runs on [Dispatchers.IO]; UI
 * observes [state]. The passphrase lives in memory only for the unlocked session.
 *
 * [clock] is the one source of "now" every valuation and every quote validity is read against; it
 * is injectable so a test can place the app inside a given trading session.
 * [yahoo] and [sources] keep one provider session and allow exercising refreshes against local HTTP.
 */
class AppRepository(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val yahoo: Yahoo = Yahoo(),
    private val sources: MultiSource = MultiSource(listOf(Airfund(), yahoo, Ft(), Morningstar())),
) {
    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var passphrase: String? = null
    private var ledger: Ledger? = null
    private var market: MarketData = MarketData()

    // The off-hours prints of the last refresh, keyed by asset id: never stored, never merged into
    // [market], and re-observed (or cleared) by every refresh. Empty unless the setting is on.
    private var offHours: Map<String, Quotes.OffHoursPrint> = emptyMap()

    // Assets whose cached series [reconcileMarket] dropped because the instrument changed. Purged
    // before valuing, so [Quotes] never sees them; the next successful refresh says so instead.
    private var requoted: Set<String> = emptySet()

    // Serializes every mutation of the shared ledger/market/config (the UI can launch e.g. sync and
    // a quote refresh concurrently). Not reentrant - a locked method must call the *Locked helpers,
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
            github = GithubConfig(owner.trim(), repo.trim(), path.trim().ifBlank { "portfolio.fin" }, branch.trim().ifBlank { "master" }),
            readPullAfter = "1h",
        )
        var created = false
        runCatching {
            val sync = container.buildSync(cfg, token.trim())
            val wc = container.workingCopy(cfg)
            // A repository the token cannot see answers 404 to the file fetch as well, which would
            // read as "no ledger yet" and start an empty one beside the real one (Go `remote login`).
            try { sync.checkAccess() } catch (e: RemoteError.Offline) {
                if (!wc.exists()) throw e
            }
            // Without a local copy, only a confirmed missing remote file permits creation.
            // A failed lookup could hide an existing ledger with a different immutable file id.
            // pullIfStale already tolerates offline access when a working copy exists.
            sync.pullIfStale()

            if (!wc.exists()) {
                wc.parentFile?.mkdirs()
                created = true
                AtomicFile.write(wc, Ledger.create(pass).toBytes())
                sync.sync(pass) // first push; auth/permission errors surface here, not swallowed
                sync.state().authError?.let { error(it) }
            }
            // Authenticate before changing saved settings/secrets. A failed reconnect retains all
            // existing working bytes and backups, including records never pushed to GitHub.
            val opened = sync.openForRead(pass)
            container.saveConfig(cfg)
            container.secretStore.putPat(token.trim())
            container.secretStore.putPassphrase(pass)
            installOpened(opened, pass, sync.state())
        }.onFailure {
            // An empty ledger this attempt started holds nothing, yet it is dirty and sealed with
            // this passphrase: kept, it would refuse every retry typed with another one. A copy
            // that existed before is never touched.
            if (created) container.discardCheckout(cfg)
            passphrase = null
            ledger = null
            market = MarketData()
            offHours = emptyMap()
            requoted = emptySet()
            _state.value = AppState.Onboarding
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

    /** Refreshes the quotes; returns what the pass wants the holder told (see [Quotes.Refresh]). */
    suspend fun refreshQuotes(): List<String> = exclusive { refreshQuotesLocked() }

    /** Quote refresh without taking the lock - call only from an already-locked method. */
    private suspend fun refreshQuotesLocked(): List<String> {
        val l = ledger ?: return emptyList()
        reconcileMarket(l.book)
        val warnings = runCatching {
            val cfg = container.loadConfig()
            val from = LocalDate.now(clock).minusYears(2)
            // The two modes differ in what they REPORT, never in what they store: the extended pass
            // returns the very same market data, plus the off-hours prints to show.
            val refreshed = Quotes.refreshDetailed(
                l.book, market, from = from, now = LocalDate.now(clock),
                referenceCcy = cfg.displayCurrency, multi = sources, yahoo = yahoo,
                extendedHours = cfg.extendedHours,
            )
            market = refreshed.market
            offHours = refreshed.offHours
            CacheSidecar.write(container.marketCacheFile(l.fileId), l.cacheKey, refreshed.market)
            val changed = requoted.mapNotNull { l.book.assets[it] }.map(Quotes::instrumentChangedWarning)
            requoted = emptySet()
            changed + refreshed.warnings
        }.getOrDefault(emptyList())
        emitReady(currentSyncState(), message = warnings.firstOrNull(), refreshing = false)
        return warnings
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

    /**
     * Persists the extended-hours opt-in, then refreshes quotes (the prints are observed by a pass,
     * never replayed from a cache) and re-emits.
     */
    suspend fun setExtendedHours(on: Boolean) = exclusive {
        val cfg = container.loadConfig()
        container.saveConfig(cfg.copy(extendedHours = on))
        refreshQuotesLocked() // already holding the lock
    }

    /**
     * One asset's detail, computed on demand (the fallback behind [AppState.Ready.assetDetails]).
     * It stands on the same off-hours prints as the last emitted valuation, so the page a holder
     * opens never contradicts the row they tapped.
     */
    fun assetDetail(assetId: String): AssetDetail? = ledger?.let {
        Gains.assetDetail(
            it.book, market, container.loadConfig().displayCurrency, LocalDate.now(clock), assetId,
            priceOverrides = Quotes.current(offHours, clock.instant()).mapValues { p -> p.value.price },
        )
    }

    suspend fun addTransaction(
        date: LocalDate, accountId: String, assetId: String?, kind: TxKind,
        qty: BigDecimal, amount: BigDecimal, ccy: String, note: String?,
    ): Result<SyncOutcome> = exclusive {
        mutateLocked("add ${kind.name}") {
            it.addTransaction(date, accountId, assetId, kind, qty, Money(amount, ccy), note?.ifBlank { null })
        }
    }

    /** Creates an account (fresh id). Rejects a reference collision; surfaced as Result.failure. */
    suspend fun addAccount(name: String, ccy: String, tax: TaxRule, aliases: List<String>): Result<SyncOutcome> = exclusive {
        mutateLocked("add account") { it.putAccount(Account(Ids.newId(), name, ccy, tax, aliases)) }
    }

    /** Edits an existing account in place (same id, last-writer-wins). */
    suspend fun editAccount(id: String, name: String, ccy: String, tax: TaxRule, aliases: List<String>): Result<SyncOutcome> = exclusive {
        mutateLocked("edit account") { it.putAccount(Account(id, name, ccy, tax, aliases)) }
    }

    /** Deletes an account; refused (Result.failure) if a transaction still references it. */
    suspend fun deleteAccount(id: String): Result<SyncOutcome> = exclusive {
        mutateLocked("delete account") { it.deleteAccount(id) }
    }

    /** Creates an asset (fresh id). Rejects a reference collision; surfaced as Result.failure. */
    suspend fun addAsset(
        kind: AssetKind, name: String, ticker: String?, isin: String?,
        aliases: List<String>, ccy: String, group: String?, withholding: Double?,
    ): Result<SyncOutcome> = exclusive {
        mutateLocked("add asset") {
            it.putAsset(Asset(Ids.newId(), kind, name, ticker, isin, aliases, ccy, group, withholding))
        }
    }

    /** Edits an existing asset in place (same id, last-writer-wins). */
    suspend fun editAsset(
        id: String, kind: AssetKind, name: String, ticker: String?, isin: String?,
        aliases: List<String>, ccy: String, group: String?, withholding: Double?,
    ): Result<SyncOutcome> = exclusive {
        mutateLocked("edit asset") {
            it.putAsset(Asset(id, kind, name, ticker, isin, aliases, ccy, group, withholding))
        }
    }

    /** Deletes an asset; refused (Result.failure) if a transaction still references it. */
    suspend fun deleteAsset(id: String): Result<SyncOutcome> = exclusive {
        mutateLocked("delete asset") { it.deleteAsset(id) }
    }

    /**
     * Shared mutate→reopen→emit path for every ledger write. The lock is already held (callers wrap
     * this in [exclusive]); a validation failure inside [fn] propagates as Result.failure without
     * touching the persisted copy (see [fin.android.remote.Sync.mutate]).
     */
    private fun mutateLocked(message: String, fn: (Ledger) -> Ledger): Result<SyncOutcome> =
        withSyncLocked { sync, pass -> sync.mutate(pass, "finador-android: $message", fn = fn) }

    /**
     * Shared run-sync-op→reopen→emit plumbing for [mutateLocked] and [syncNow]: builds the [Sync]
     * from stored config/secrets, runs [op], then reopens the (possibly merged) working copy and
     * re-emits Ready. The lock is already held.
     */
    private fun withSyncLocked(op: (Sync, String) -> SyncOutcome): Result<SyncOutcome> {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return Result.failure(IllegalStateException("no token"))
        val pass = passphrase ?: return Result.failure(IllegalStateException("locked"))
        return runCatching {
            val sync = container.buildSync(cfg, token)
            val outcome = op(sync, pass)
            val opened = Ledger.open(container.workingCopy(cfg).readBytes(), pass)
            reconcileMarket(opened.book, ledger?.book)
            ledger = opened
            val cache = container.marketCacheFile(opened.fileId)
            runCatching { CacheSidecar.write(cache, opened.cacheKey, market) }.onFailure {
                // The ledger has already been committed: a regenerable cache failure must not
                // invite retrying the same transaction. Discard old instrument data best-effort.
                cache.delete()
            }
            emitReady(sync.state(), outcome.message, refreshing = false)
            outcome
        }
    }

    suspend fun syncNow(): Result<SyncOutcome> = exclusive {
        withSyncLocked { sync, pass -> sync.sync(pass) }
    }

    suspend fun forget() = exclusive {
        container.secretStore.purge()
        container.clearConfig()
        passphrase = null
        ledger = null
        market = MarketData()
        offHours = emptyMap()
        requoted = emptySet()
        _state.value = AppState.Onboarding
    }

    fun currentBook(): Book? = ledger?.book

    private fun unlockInternal(cfg: RemoteConfig, pass: String) {
        val token = container.secretStore.getPat() ?: error("no token")
        val sync = container.buildSync(cfg, token)
        val opened = sync.openForRead(pass)
        installOpened(opened, pass, sync.state())
    }

    private fun installOpened(opened: Ledger, pass: String, syncState: SyncState) {
        passphrase = pass
        ledger = opened
        market = CacheSidecar.read(container.marketCacheFile(opened.fileId), opened.cacheKey) ?: MarketData()
        offHours = emptyMap()
        requoted = emptySet()
        reconcileMarket(opened.book)
        emitReady(syncState, message = null, refreshing = true) // quotes refreshed asynchronously next
    }

    /** Invalidate before any valuation, even when fetching the replacement instrument fails. */
    private fun reconcileMarket(book: Book, previous: Book? = null) {
        val ids = market.invalidatedAssets(book, previous)
        // Only a security still quoted had a series to lose to another instrument; a removed or
        // statement-valued asset leaves nothing to tell the holder about.
        requoted += ids.filter { id ->
            val asset = book.assets[id]
            (id in market.prices || id in market.dividends) && asset != null &&
                asset.kind == AssetKind.SECURITY && !(asset.ticker.isNullOrBlank() && asset.isin.isNullOrBlank())
        }
        market = market.withoutAssets(ids)
        offHours = offHours - ids
    }

    private fun currentSyncState(): SyncState {
        val cfg = container.loadConfig()
        val token = container.secretStore.getPat() ?: return SyncState()
        return container.buildSync(cfg, token).state()
    }

    private fun emitReady(syncState: SyncState, message: String?, refreshing: Boolean) {
        val l = ledger ?: return
        val today = LocalDate.now(clock)
        val ref = container.loadConfig().displayCurrency // null → engine falls back to book/EUR
        // One screen, one price: the off-hours prints still in force drive the valuation AND every
        // figure shown beside it (the gains table's value column, each detail page's price/value).
        // Performance - [computePerf], the day move, the period gains - stays on published closes.
        // "Still in force" is the point: a print is observed once and the screen is re-emitted
        // many times (a sync, an edit, a settings change), so its validity is re-read here, at the
        // instant it would be shown, and an expired one simply leaves the close in charge.
        val prints = Quotes.current(offHours, clock.instant())
        val overrides = prints.mapValues { it.value.price }
        val valuation = Valuator.value(
            l.book, market, referenceCcy = ref, at = today, byGroup = true,
            priceOverrides = overrides,
        )
        val perf = computePerf(l.book, today, ref)
        // Gains are a pure read over the same engine; never let them crash the UI.
        val gains = runCatching {
            Gains.report(l.book, market, referenceCcy = ref, today = today, priceOverrides = overrides)
        }.getOrNull()
        // Precompute every held-security detail now (reusing the valuation's positions, so no extra
        // book folds) - opening an asset's page is then an instant map lookup, not a computation.
        val assetDetails = runCatching {
            l.book.assets.values
                .mapNotNull { a ->
                    Gains.assetDetail(l.book, market, ref, today, a.id, valuation.positions, overrides)
                        ?.let { a.id to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
        // The rates behind the total: read back from the same cached series the valuation crossed.
        val fxRates = FxRates.held(
            valuation.positions.map { it.ccy },
            valuation.referenceCcy,
            market.fx,
            today,
        )
        _state.value = AppState.Ready(
            valuation, perf, gains, l.book, syncState, message, refreshing, assetDetails, fxRates,
            prints,
        )
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
