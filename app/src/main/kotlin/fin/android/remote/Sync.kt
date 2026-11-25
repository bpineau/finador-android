package fin.android.remote

import fin.android.format.BadPasswordOrCorruptException
import fin.android.format.Conflict
import fin.android.format.Ledger
import fin.android.crypto.Bytes
import fin.android.crypto.Hashes
import fin.android.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

/** Persisted sync state for one working copy. */
@Serializable
data class SyncState(
    val sha: String? = null,
    val lastPull: String? = null,
    val dirty: Boolean = false,
    /**
     * Message of the last GitHub auth failure (expired/under-scoped token); null when auth is
     * healthy. Set so the UI can show a persistent "re-login" banner while local reads keep
     * working; cleared by the next successful fetch or push.
     */
    val authError: String? = null,
    /** SHA-256 of the exact bytes last fetched or pushed; absent in older state files. */
    val synced: String? = null,
)

/** Outcome of a mutate/sync, for UI feedback. */
data class SyncOutcome(val pushed: Boolean, val dirty: Boolean, val message: String)

/**
 * Reconciles a local working copy of the ledger with a [Backend]. Reads pull when stale; writes
 * pull-before / push-after with conflict→merge; offline writes succeed locally (dirty) and push on
 * the next online access. Blocking - call on a background dispatcher.
 */
class Sync(
    private val backend: Backend,
    private val workingCopy: File,
    private val stateFile: File,
    private val readPullAfter: Duration,
    private val now: () -> Instant = { Instant.now() },
) {
    /**
     * The persisted state, or a CONSERVATIVE default when it cannot be read.
     *
     * An unreadable state file used to throw out of here, and every path in this class starts by
     * calling it: one short write (a process killed mid-`writeText`, which is why [saveState] is
     * atomic now) made the unlock itself fail, for ever, since nothing rewrites the file until a
     * sync runs. Absent with a local copy, or UNREADABLE, it must mean DIRTY: the flag it lost is
     * the one that stops a pull from overwriting records the remote has
     * never seen, and a needless merge costs one push where a needless pull costs the data.
     */
    fun state(): SyncState {
        if (!stateFile.exists()) return SyncState(dirty = workingCopy.exists())
        val saved = try {
            json.decodeFromString(SyncState.serializer(), stateFile.readText())
        } catch (_: Exception) {
            SyncState(dirty = true)
        }
        val changed = saved.synced != null && workingCopy.exists() &&
            runCatching { digest(workingCopy.readBytes()) != saved.synced }.getOrDefault(true)
        return if (changed) saved.copy(dirty = true) else saved
    }

    private fun saveState(s: SyncState) =
        AtomicFile.writeText(stateFile, json.encodeToString(SyncState.serializer(), s))

    /** [Backend.checkAccess]; touches neither the working copy nor the state. */
    fun checkAccess() = backend.checkAccess()

    fun hasLocalCopy(): Boolean = workingCopy.exists() || AtomicFile.backupOf(workingCopy).exists()

    /** Null only when GitHub confirmed the file is missing and there is no local ledger. */
    fun openIfPresent(passphrase: String): Ledger? {
        recoverLoneBackup(passphrase)?.let { return it }
        val st = state()
        val age = st.lastPull?.let { runCatching { Duration.between(Instant.parse(it), now()) }.getOrNull() }
        val fresh = age != null && !age.isNegative && age < readPullAfter
        if (!workingCopy.exists() || (!st.dirty && !fresh)) {
            try {
                val fetched = backend.fetch()
                return installFetched(fetched, passphrase, st)
            } catch (_: RemoteError.Missing) {
                if (!workingCopy.exists()) return null
            } catch (e: RemoteError.Offline) {
                if (!workingCopy.exists()) throw e
            } catch (e: RemoteError.Auth) {
                saveState(st.copy(authError = e.message))
                if (!workingCopy.exists()) throw e
            }
        }
        return try {
            Ledger.open(workingCopy.readBytes(), passphrase)
        } catch (e: BadPasswordOrCorruptException) {
            if (!AtomicFile.backupOf(workingCopy).exists()) throw e
            try { recoverBackup(passphrase) } catch (_: BadPasswordOrCorruptException) { throw e }
        }
    }

    private fun recoverLoneBackup(passphrase: String): Ledger? =
        if (!workingCopy.exists() && AtomicFile.backupOf(workingCopy).exists()) recoverBackup(passphrase) else null

    private fun recoverBackup(passphrase: String): Ledger {
        val bytes = AtomicFile.backupOf(workingCopy).readBytes()
        val recovered = Ledger.open(bytes, passphrase)
        saveState(state().copy(dirty = true))
        AtomicFile.write(workingCopy, bytes)
        return recovered
    }

    /** Verify the remote bytes before replacing either the working copy or its last good backup. */
    private fun installFetched(f: Fetched, passphrase: String, st: SyncState): Ledger {
        val opened = Ledger.open(f.data, passphrase)
        writeCopy(f.data)
        saveState(st.copy(sha = f.version, lastPull = now().toString(), dirty = false,
            synced = digest(f.data), authError = null))
        return opened
    }

    fun openForRead(passphrase: String): Ledger = requireNotNull(openIfPresent(passphrase)) {
        "no local copy and remote unavailable"
    }

    /**
     * Applies [fn] to the current ledger and pushes. On a remote conflict, re-fetches and merges
     * (bounded retries). Offline → the change is kept locally (dirty) and pushed later.
     */
    fun mutate(
        passphrase: String,
        message: String,
        resolve: (Conflict) -> Int = { 0 },
        fn: (Ledger) -> Ledger,
    ): SyncOutcome {
        recoverLoneBackup(passphrase)
        var st = state()
        val current = try {
            val f = backend.fetch()
            val remote = Ledger.open(f.data, passphrase)
            st = st.copy(sha = f.version, lastPull = now().toString(), authError = null)
            if (st.dirty && workingCopy.exists()) {
                Ledger.open(workingCopy.readBytes(), passphrase).merge(remote, resolve)
            } else remote
        } catch (_: RemoteError.Missing) {
            st = st.copy(sha = null)
            null
        } catch (_: RemoteError.Offline) {
            null
        } catch (e: RemoteError.Auth) {
            st = st.copy(authError = e.message)
            null
        }
        val local = fn(current ?: Ledger.open(workingCopy.readBytes(), passphrase))
        val bytes = local.toBytes()
        st = st.copy(dirty = true)
        saveState(st)
        writeCopy(bytes)
        return pushSaved(local, bytes, st, passphrase, message, resolve)
    }

    /** Retry only the push and merge. The user's mutation has already been written exactly once. */
    private fun pushSaved(
        saved: Ledger, savedBytes: ByteArray, savedState: SyncState, passphrase: String,
        message: String, resolve: (Conflict) -> Int,
    ): SyncOutcome {
        var st = savedState
        var local = saved
        var bytes = savedBytes
        /** The change is written locally but could not reach the remote: keep it dirty.
         *  [authError] records a token rejection; an offline failure preserves any existing one. */
        fun keptLocally(message: String, authError: String? = st.authError): SyncOutcome {
            saveState(st.copy(dirty = true, authError = authError))
            return SyncOutcome(pushed = false, dirty = true, message = message)
        }

        repeat(MAX_PUSH_TRIES) {
            try {
                val newSha = backend.push(bytes, st.sha, message)
                saveState(st.copy(sha = newSha, lastPull = now().toString(), dirty = false,
                    synced = digest(bytes), authError = null))
                return SyncOutcome(pushed = true, dirty = false, message = "synced")
            } catch (e: RemoteError.Conflict) {
                // Going offline (or losing auth) mid-reconcile must still honor the dirty guarantee.
                val remote = try {
                    backend.fetch()
                } catch (_: RemoteError.Missing) {
                    st = st.copy(sha = null)
                    return@repeat
                } catch (off: RemoteError.Offline) {
                    return keptLocally("saved locally (offline) - will push later")
                } catch (auth: RemoteError.Auth) {
                    return keptLocally("saved locally - GitHub token rejected, re-login to push", auth.message)
                }
                local = try {
                    local.merge(Ledger.open(remote.data, passphrase), resolve)
                } catch (e: Exception) {
                    return keptLocally("saved locally - cannot merge remote ledger: ${e.message}")
                }
                bytes = local.toBytes()
                writeCopy(bytes)
                st = st.copy(sha = remote.version)
                saveState(st)
            } catch (e: RemoteError.Offline) {
                return keptLocally("saved locally (offline) - will push later")
            } catch (e: RemoteError.Auth) {
                return keptLocally("saved locally - GitHub token rejected, re-login to push", e.message)
            }
        }
        return keptLocally("could not resolve remote conflict - kept locally")
    }

    /** Force pull (and push if there are unpushed local changes). */
    fun sync(passphrase: String, resolve: (Conflict) -> Int = { 0 }): SyncOutcome {
        recoverLoneBackup(passphrase)
        var st = state()
        val fetched = try {
            backend.fetch()
        } catch (e: RemoteError.Missing) {
            null
        } catch (e: RemoteError.Offline) {
            return SyncOutcome(pushed = false, dirty = st.dirty, message = "offline")
        } catch (e: RemoteError.Auth) {
            saveState(st.copy(authError = e.message))
            return SyncOutcome(pushed = false, dirty = st.dirty, message = "GitHub token rejected - re-login")
        }
        st = st.copy(authError = null) // the fetch went through: auth is healthy again

        if (fetched != null && (!st.dirty || !workingCopy.exists())) {
            installFetched(fetched, passphrase, st)
            return SyncOutcome(pushed = false, dirty = false, message = "up to date")
        }
        if (!hasLocalCopy() && fetched == null) {
            return SyncOutcome(pushed = false, dirty = st.dirty, message = "no ledger to sync")
        }

        val localBytes = workingCopy.readBytes()
        var local = Ledger.open(localBytes, passphrase)
        var bytes = localBytes
        st = st.copy(dirty = true, sha = fetched?.version)
        if (fetched != null) {
            local = local.merge(Ledger.open(fetched.data, passphrase), resolve)
            bytes = local.toBytes()
        }
        saveState(st)
        if (fetched != null) writeCopy(bytes)
        return pushSaved(local, bytes, st, passphrase, "finador-android: sync", resolve)
    }

    /**
     * Replaces the working copy, atomically and keeping the previous contents under `.bak` (the Go
     * reference does exactly this, `internal/store/store.go`). A half-written ledger authenticates
     * as nothing, and [openForRead] refuses to pull over a dirty copy, so the truncation a plain
     * write risks would be unrecoverable from inside the app.
     */
    private fun writeCopy(data: ByteArray) = AtomicFile.write(workingCopy, data, backup = true)

    companion object {
        private const val MAX_PUSH_TRIES = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private fun digest(data: ByteArray): String = Bytes.toHex(Hashes.sha256(data))
    }
}
