package fin.android.remote

import fin.android.format.Conflict
import fin.android.format.Ledger
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
    fun state(): SyncState =
        if (stateFile.exists()) json.decodeFromString(SyncState.serializer(), stateFile.readText()) else SyncState()

    private fun saveState(s: SyncState) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(SyncState.serializer(), s))
    }

    /** Pulls into the working copy if online and stale (missing copy, past readPullAfter). */
    fun pullIfStale() {
        val st = state()
        val sinceLastPull = st.lastPull?.let { Duration.between(Instant.parse(it), now()) }
        // Fresh only within [0, readPullAfter): a future lastPull (clock skew / restored backup) is
        // treated as stale rather than suppressing pulls forever.
        val fresh = workingCopy.exists() && sinceLastPull != null &&
            !sinceLastPull.isNegative && sinceLastPull < readPullAfter
        if (fresh) return
        try {
            val f = backend.fetch()
            writeCopy(f.data)
            saveState(st.copy(sha = f.version, lastPull = now().toString(), authError = null))
        } catch (e: RemoteError.Missing) {
            // No remote file yet - keep whatever local copy exists (possibly none).
        } catch (e: RemoteError.Offline) {
            if (!workingCopy.exists()) throw e // can't read without any copy
        } catch (e: RemoteError.Auth) {
            // A rejected token must not lock the user out of their local data: record it (the UI
            // shows a persistent re-login banner) and read the local copy. Without any copy there
            // is nothing to show - let the error surface.
            saveState(st.copy(authError = e.message))
            if (!workingCopy.exists()) throw e
        }
    }

    /** Opens the ledger for reading, pulling first if stale. */
    fun openForRead(passphrase: String): Ledger {
        pullIfStale()
        require(workingCopy.exists()) { "no local copy and remote unavailable" }
        return Ledger.open(workingCopy.readBytes(), passphrase)
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
        var st = state()
        // Pull fresh first so we mutate on top of the latest remote (best-effort when online).
        try {
            val f = backend.fetch()
            writeCopy(f.data)
            st = st.copy(sha = f.version, lastPull = now().toString(), authError = null)
            saveState(st)
        } catch (e: RemoteError.Missing) {
            st = st.copy(sha = null)
        } catch (e: RemoteError.Offline) {
            // proceed on the local copy
        } catch (e: RemoteError.Auth) {
            // proceed on the local copy; the push below will fail too and keep the change dirty
        }

        require(workingCopy.exists()) { "no local copy to mutate and remote unavailable" }
        var local = fn(Ledger.open(workingCopy.readBytes(), passphrase))
        var bytes = local.toBytes() // serialize once; re-derived only after a merge
        writeCopy(bytes)

        /** The change is written locally but could not reach the remote: keep it dirty.
         *  [authError] records a token rejection; an offline failure preserves any existing one. */
        fun keptLocally(message: String, authError: String? = st.authError): SyncOutcome {
            saveState(st.copy(dirty = true, authError = authError))
            return SyncOutcome(pushed = false, dirty = true, message = message)
        }

        repeat(MAX_PUSH_TRIES) {
            try {
                val newSha = backend.push(bytes, st.sha, message)
                saveState(st.copy(sha = newSha, lastPull = now().toString(), dirty = false, authError = null))
                return SyncOutcome(pushed = true, dirty = false, message = "synced")
            } catch (e: RemoteError.Conflict) {
                // Going offline (or losing auth) mid-reconcile must still honor the dirty guarantee.
                val remote = try {
                    backend.fetch()
                } catch (off: RemoteError.Offline) {
                    return keptLocally("saved locally (offline) - will push later")
                } catch (auth: RemoteError.Auth) {
                    return keptLocally("saved locally - GitHub token rejected, re-login to push", auth.message)
                }
                local = local.merge(Ledger.open(remote.data, passphrase), resolve)
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

        if (!st.dirty) {
            if (fetched != null) {
                writeCopy(fetched.data)
                saveState(st.copy(sha = fetched.version, lastPull = now().toString()))
            } else if (workingCopy.exists()) {
                // remote missing but we have a local copy → first push (guard offline like elsewhere)
                try {
                    val newSha = backend.push(workingCopy.readBytes(), null, "finador-android: initial push")
                    saveState(st.copy(sha = newSha, lastPull = now().toString()))
                } catch (e: RemoteError.Offline) {
                    return SyncOutcome(pushed = false, dirty = false, message = "offline")
                }
            }
            return SyncOutcome(pushed = false, dirty = false, message = "up to date")
        }

        // dirty: reconcile local with remote and push
        var local = Ledger.open(workingCopy.readBytes(), passphrase)
        if (fetched != null) {
            local = local.merge(Ledger.open(fetched.data, passphrase), resolve)
            st = st.copy(sha = fetched.version)
        }
        val bytes = local.toBytes()
        writeCopy(bytes)
        return try {
            val newSha = backend.push(bytes, st.sha, "finador-android: sync")
            saveState(st.copy(sha = newSha, lastPull = now().toString(), dirty = false))
            SyncOutcome(pushed = true, dirty = false, message = "synced")
        } catch (e: RemoteError.Offline) {
            saveState(st.copy(dirty = true))
            SyncOutcome(pushed = false, dirty = true, message = "offline - will push later")
        } catch (e: RemoteError.Auth) {
            saveState(st.copy(dirty = true, authError = e.message))
            SyncOutcome(pushed = false, dirty = true, message = "saved locally - GitHub token rejected, re-login to push")
        }
    }

    private fun writeCopy(data: ByteArray) {
        workingCopy.parentFile?.mkdirs()
        workingCopy.writeBytes(data)
    }

    companion object {
        private const val MAX_PUSH_TRIES = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
