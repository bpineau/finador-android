package fin.android.remote

/** Opaque remote version (GitHub blob sha). */
typealias Version = String

/** Result of fetching the remote file. */
data class Fetched(val data: ByteArray, val version: Version)

/** Remote failures, kept distinct so the UI never confuses "offline" with "bad token". */
sealed class RemoteError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The base version moved under us (concurrent write) - caller must re-fetch and merge. */
    class Conflict(message: String = "remote changed (sha mismatch)") : RemoteError(message)

    /** The file or repo path does not exist yet (404). */
    class Missing(message: String = "remote file not found") : RemoteError(message)

    /** Authentication/authorization failed (401/403) - invalid or under-scoped token. */
    class Auth(message: String) : RemoteError(message)

    /** Network/DNS/server error - treat as offline (work locally, push later). */
    class Offline(message: String, cause: Throwable? = null) : RemoteError(message, cause)
}

/**
 * Transport seam over a single remote-hosted ledger file. Blocking by design - callers invoke it
 * on a background dispatcher. One network implementation today (GitHub Contents API).
 */
interface Backend {
    /** @throws RemoteError.Missing if absent; RemoteError.Auth/Offline on failure. */
    fun fetch(): Fetched

    /** Uploads [data]; [base] is the expected current version (null to create).
     *  @return the new version. @throws RemoteError.Conflict on a stale base; Auth/Offline otherwise. */
    fun push(data: ByteArray, base: Version?, message: String): Version

    fun describe(): String
}
