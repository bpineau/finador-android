package fin.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class AesGcmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 1).toByte() }
    private val aad = "header-aad".toByteArray()
    private val pt = "hello finador".toByteArray()

    @Test
    fun roundTrip() {
        val sealed = AesGcm.seal(key, nonce, pt, aad)
        assertArrayEquals(pt, AesGcm.open(key, nonce, sealed, aad))
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperFails() {
        val sealed = AesGcm.seal(key, nonce, pt, aad)
        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()
        AesGcm.open(key, nonce, sealed, aad)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongAadFails() {
        val sealed = AesGcm.seal(key, nonce, pt, aad)
        AesGcm.open(key, nonce, sealed, "other-aad".toByteArray())
    }
}
