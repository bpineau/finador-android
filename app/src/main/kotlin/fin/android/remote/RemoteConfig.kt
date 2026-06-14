package fin.android.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration

@Serializable
data class GithubConfig(
    val owner: String,
    val repo: String,
    val path: String = "portfolio.fin",
    val branch: String = "main",
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
) {
    val isGithub: Boolean get() = source == "github" && github != null

    fun readPullAfterDuration(): Duration = parseGoDuration(readPullAfter)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun load(file: File): RemoteConfig =
            if (file.exists()) json.decodeFromString(serializer(), file.readText()) else RemoteConfig()

        fun save(file: File, cfg: RemoteConfig) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), cfg))
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
