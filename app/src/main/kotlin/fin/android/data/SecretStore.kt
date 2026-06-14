package fin.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * At-rest storage for the two long-lived secrets: the GitHub PAT and the ledger passphrase. Both
 * are entered once and never retyped; the app gates access with a BiometricPrompt at launch (UI
 * layer). [purge] is the "forget" action.
 */
interface SecretStore {
    fun putPat(token: String)
    fun getPat(): String?
    fun putPassphrase(passphrase: String)
    fun getPassphrase(): String?
    fun hasPassphrase(): Boolean
    fun purge()
}

/** Process-memory implementation for tests. */
class InMemorySecretStore : SecretStore {
    private var pat: String? = null
    private var passphrase: String? = null
    override fun putPat(token: String) { pat = token }
    override fun getPat(): String? = pat
    override fun putPassphrase(passphrase: String) { this.passphrase = passphrase }
    override fun getPassphrase(): String? = passphrase
    override fun hasPassphrase(): Boolean = passphrase != null
    override fun purge() { pat = null; passphrase = null }
}

/**
 * Android implementation backed by EncryptedSharedPreferences (Keystore-bound master key, so the
 * secrets are encrypted at rest by hardware where available). Biometric unlock is enforced by the
 * UI before these are read.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    @Suppress("DEPRECATION") // Jetpack Security is deprecated but the current shipping option for EncryptedSharedPreferences
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "finador_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun putPat(token: String) { prefs.edit().putString(KEY_PAT, token).apply() }
    override fun getPat(): String? = prefs.getString(KEY_PAT, null)
    override fun putPassphrase(passphrase: String) { prefs.edit().putString(KEY_PASS, passphrase).apply() }
    override fun getPassphrase(): String? = prefs.getString(KEY_PASS, null)
    override fun hasPassphrase(): Boolean = prefs.contains(KEY_PASS)
    override fun purge() { prefs.edit().clear().apply() }

    private companion object {
        const val KEY_PAT = "github_pat"
        const val KEY_PASS = "ledger_passphrase"
    }
}
