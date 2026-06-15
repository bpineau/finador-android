package fin.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden KDF vector from FORMAT.md §9.1 - freezes Argon2id + HKDF against the reference impl. */
class KdfTest {
    @Test
    fun kdfVector() {
        val salt = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
        val pw = "correct horse battery staple".toByteArray(Charsets.UTF_8)

        val master = Argon2.hash(pw, salt, t = 3, m = 65536, p = 4, len = 32)
        assertEquals("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e", Bytes.toHex(master))

        val keyLog = Hkdf.sha256(master, null, "finador-ledger-v2".toByteArray(Charsets.US_ASCII), 32)
        assertEquals("156457f5a4060765068beda9f37d0fa8257deb767190905231e1fc1e4327167b", Bytes.toHex(keyLog))

        val keyCache = Hkdf.sha256(master, null, "finador-cache-v2".toByteArray(Charsets.US_ASCII), 32)
        assertEquals("7c39ddca718165d3a72ccd023957c2af4814c198ce871c0dda490d54e1b00b3a", Bytes.toHex(keyCache))
    }
}
