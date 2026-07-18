package fin.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * One-shot read of secrets written by the deprecated Jetpack Security
 * `EncryptedSharedPreferences` ("finador_secrets"), so [KeystoreSecretStore] can re-encrypt them
 * into its own format on first launch. Kept in its own file so it - and the
 * `androidx.security:security-crypto` dependency it needs - can be deleted together once every
 * installed device has run a post-migration release.
 */
internal object LegacySecretMigration {
    private const val LEGACY_PREFS = "finador_secrets"
    private const val KEY_PAT = "github_pat"
    private const val KEY_PASS = "ledger_passphrase"

    data class Secrets(val pat: String?, val passphrase: String?)

    /** The legacy secrets, or null when there is no readable legacy store (e.g. fresh install). */
    fun read(context: Context): Secrets? = try {
        @Suppress("DEPRECATION") // reading the legacy store is this file's whole purpose
        val legacy = androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS,
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val pat = legacy.getString(KEY_PAT, null)
        val pass = legacy.getString(KEY_PASS, null)
        if (pat == null && pass == null) null else Secrets(pat, pass)
    } catch (_: Exception) {
        null // unreadable legacy store (lost master key, first install): nothing to migrate
    }

    /** Deletes the legacy store; call after a successful (or empty) migration. */
    fun wipe(context: Context) {
        runCatching {
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit { clear() }
            context.deleteSharedPreferences(LEGACY_PREFS)
        }
    }
}
