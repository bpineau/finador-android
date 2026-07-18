package fin.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
 * Android implementation: values are AES-256-GCM-encrypted under a key that lives only in the
 * Android Keystore (hardware-backed where available, never extractable), then stored as
 * base64(iv ‖ ciphertext ‖ tag) in plain SharedPreferences. This replaces the deprecated Jetpack
 * Security `EncryptedSharedPreferences` with the primitives it wrapped - same at-rest guarantee,
 * no deprecated dependency. Biometric unlock is enforced by the UI before these are read.
 *
 * A legacy `EncryptedSharedPreferences` store ("finador_secrets") is migrated once on first
 * access - see [LegacySecretMigration].
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-shot migration from the deprecated EncryptedSharedPreferences store. Runs before any
        // read; a failed read leaves the new store empty (the user re-onboards, nothing corrupts).
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            LegacySecretMigration.read(context)?.let { legacy ->
                legacy.pat?.let(::putPat)
                legacy.passphrase?.let(::putPassphrase)
            }
            LegacySecretMigration.wipe(context)
            prefs.edit { putBoolean(KEY_MIGRATED, true) }
        }
    }

    override fun putPat(token: String) = put(KEY_PAT, token)
    override fun getPat(): String? = get(KEY_PAT)
    override fun putPassphrase(passphrase: String) = put(KEY_PASS, passphrase)
    override fun getPassphrase(): String? = get(KEY_PASS)
    override fun hasPassphrase(): Boolean = prefs.contains(KEY_PASS)
    override fun purge() { prefs.edit { clear() } }

    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Keystore generates a fresh random IV
        val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + sealed
        prefs.edit { putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    /** Null when absent; also null (never a crash) if the Keystore key was lost or rotated. */
    private fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Loads (or creates once) the AES-256 key inside the Android Keystore. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "finador_secrets_v2"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "finador_secrets_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LEN = 12
        const val KEY_PAT = "github_pat"
        const val KEY_PASS = "ledger_passphrase"
        const val KEY_MIGRATED = "legacy_migrated"
    }
}
