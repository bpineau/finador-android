package fin.android.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id key derivation, pure-Java via Bouncy Castle's lightweight API. Uses Argon2 version
 * 1.3 (0x13) to match golang.org/x/crypto/argon2.IDKey. Runs identically on the host JVM (unit
 * tests) and on Android (no JNI).
 */
object Argon2 {
    fun hash(password: ByteArray, salt: ByteArray, t: Int, m: Int, p: Int, len: Int = 32): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(t)
            .withMemoryAsKB(m)
            .withParallelism(p)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(len)
        gen.generateBytes(password, out)
        return out
    }
}
