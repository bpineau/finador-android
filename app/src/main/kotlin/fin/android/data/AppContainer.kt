package fin.android.data

import android.content.Context
import fin.android.crypto.Bytes
import fin.android.crypto.Hashes
import fin.android.market.CacheSidecar
import fin.android.remote.GitHubBackend
import fin.android.remote.RemoteConfig
import fin.android.remote.Sync
import java.io.File

/**
 * Manual dependency container (no Hilt). Owns the app's file locations, the Keystore-backed secret
 * store, and the factories that assemble a [Sync] from the saved [RemoteConfig] + the stored token.
 */
class AppContainer(context: Context) {
    val secretStore: SecretStore = KeystoreSecretStore(context)

    private val configFile = File(context.filesDir, "config.json")
    private val checkoutDir = File(context.filesDir, "checkout")
    private val marketCacheDir = File(context.cacheDir, "finador")

    val repository: AppRepository by lazy { AppRepository(this) }

    fun loadConfig(): RemoteConfig = RemoteConfig.load(configFile)
    fun saveConfig(cfg: RemoteConfig) = RemoteConfig.save(configFile, cfg)
    fun clearConfig() { configFile.delete() }

    fun buildSync(cfg: RemoteConfig, token: String): Sync {
        val gh = requireNotNull(cfg.github) { "github config required" }
        val backend = GitHubBackend(gh.owner, gh.repo, gh.path, gh.branch, token)
        checkoutDir.mkdirs()
        return Sync(backend, workingCopy(cfg), File(checkoutDir, "${slug(cfg)}.state.json"), cfg.readPullAfterDuration())
    }

    fun workingCopy(cfg: RemoteConfig): File = File(checkoutDir, "${slug(cfg)}.fin")

    fun marketCacheFile(fileId: ByteArray): File {
        marketCacheDir.mkdirs()
        return File(marketCacheDir, CacheSidecar.cacheFileName(fileId))
    }

    private fun slug(cfg: RemoteConfig): String {
        val gh = requireNotNull(cfg.github)
        return Bytes.toHex(Hashes.sha256("${gh.owner}/${gh.repo}/${gh.path}".toByteArray())).take(16)
    }
}
