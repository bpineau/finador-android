package fin.android.data

import android.content.Context
import fin.android.market.CacheSidecar
import fin.android.remote.GitHubBackend
import fin.android.remote.Backend
import fin.android.remote.GithubConfig
import fin.android.remote.RemoteConfig
import fin.android.remote.Sync
import fin.android.storage.AtomicFile
import java.io.File

/**
 * Manual dependency container (no Hilt). Owns the app's file locations, the Keystore-backed secret
 * store, and the factories that assemble a [Sync] from the saved [RemoteConfig] + the stored token.
 */
class AppContainer internal constructor(
    filesDir: File,
    cacheDir: File,
    val secretStore: SecretStore,
    private val backend: (GithubConfig, String) -> Backend = { gh, token ->
        GitHubBackend(gh.owner, gh.repo, gh.path, gh.branch, token)
    },
) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir, KeystoreSecretStore(context))

    private val configFile = File(filesDir, "config.json")
    private val checkoutDir = File(filesDir, "checkout")
    private val checkouts = CheckoutPaths(checkoutDir)
    private val marketCacheDir = File(cacheDir, "finador")

    val repository: AppRepository by lazy { AppRepository(this) }

    fun loadConfig(): RemoteConfig {
        val cfg = RemoteConfig.load(configFile)
        if (!cfg.isGithub || cfg.checkoutLayoutVersion != 0) return cfg
        checkouts.migrateLegacy(requireNotNull(cfg.github))
        // Record completion even when a destination already existed and the legacy bytes were
        // retained. A later selection must never claim that unidentified copy for another branch.
        val current = cfg.copy(checkoutLayoutVersion = CHECKOUT_LAYOUT_VERSION)
        RemoteConfig.save(configFile, current)
        return current
    }
    fun saveConfig(cfg: RemoteConfig) {
        loadConfig() // preserve the old branch's legacy checkout before replacing its identity
        RemoteConfig.save(configFile, cfg.copy(checkoutLayoutVersion = CHECKOUT_LAYOUT_VERSION))
    }
    fun clearConfig() {
        loadConfig() // Forget must retain the known branch ownership of any legacy local edits
        configFile.delete()
        AtomicFile.backupOf(configFile).delete()
    }

    fun buildSync(cfg: RemoteConfig, token: String): Sync {
        loadConfig() // migrate only the saved configuration, never the newly entered branch
        val gh = requireNotNull(cfg.github) { "github config required" }
        val remote = backend(gh, token)
        checkoutDir.mkdirs()
        return Sync(remote, workingCopy(cfg), checkouts.state(gh), cfg.readPullAfterDuration())
    }

    fun workingCopy(cfg: RemoteConfig): File = checkouts.workingCopy(requireNotNull(cfg.github))

    fun marketCacheFile(fileId: ByteArray): File {
        marketCacheDir.mkdirs()
        return File(marketCacheDir, CacheSidecar.cacheFileName(fileId))
    }

    private companion object {
        const val CHECKOUT_LAYOUT_VERSION = 1
    }
}
