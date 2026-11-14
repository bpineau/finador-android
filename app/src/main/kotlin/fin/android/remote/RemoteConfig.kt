package fin.android.remote

import fin.android.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration

@Serializable
data class GithubConfig(
    val owner: String,
    val repo: String,
    val path: String = "portfolio.fin",
    val branch: String = "master",
)

/**
 * Where the ledger lives, persisted outside the encrypted file (we need the location before we can
 * decrypt). `source` is "local" (default) or "github".
 */
@Serializable
data class RemoteConfig(
    val source: String = "local",
    val github: GithubConfig? = null,
    val readPullAfter: String = "1h",
    /** Override for the currency every value/gain is shown in; null falls back to the book's. */
    val displayCurrency: String? = null,
    /**
     * Opt-in: value US-listed lines at their pre/post-market print when it is fresher than the
     * regular one (parity with the Go reference's `value --extended`). Off by default; an off-hours
     * print is shown labelled and never stored.
     */
    val extendedHours: Boolean = false,
    /** Local checkout naming version; 0 identifies a saved pre-migration branch owner. */
    val checkoutLayoutVersion: Int = 0,
) {
    val isGithub: Boolean get() = source == "github" && github != null

    fun readPullAfterDuration(): Duration = parseGoDuration(readPullAfter)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** Recovers a last good configuration, or offers onboarding while retaining local data. */
        fun load(file: File): RemoteConfig {
            read(file)?.let { return it }
            val backup = AtomicFile.backupOf(file)
            val recovered = read(backup) ?: return RemoteConfig()
            // Do not back up the truncated current file over the valid recovery copy.
            AtomicFile.write(file, backup.readBytes())
            return recovered
        }

        private fun read(file: File): RemoteConfig? = runCatching {
            json.decodeFromString(serializer(), file.readText())
        }.getOrNull()

        fun save(file: File, cfg: RemoteConfig) {
            load(file) // recover first so the backup keeps valid previous settings
            AtomicFile.writeText(file, json.encodeToString(serializer(), cfg), backup = read(file) != null)
        }

        /** Parses a Go-style duration of whole h/m/s units (e.g. "1h", "30m", "45s"). */
        fun parseGoDuration(s: String): Duration {
            val m = Regex("^(\\d+)([hms])$").matchEntire(s.trim())
                ?: return Duration.ofHours(1)
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "h" -> Duration.ofHours(n)
                "m" -> Duration.ofMinutes(n)
                else -> Duration.ofSeconds(n)
            }
        }
    }
}
