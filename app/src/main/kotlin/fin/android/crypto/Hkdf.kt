package fin.android.crypto

/**
 * HKDF-SHA256 (RFC 5869). A nil [salt] is replaced by HashLen (32) zero bytes, matching
 * Go's golang.org/x/crypto/hkdf behaviour used by the reference implementation.
 */
object Hkdf {
    private const val HASH_LEN = 32

    fun sha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray {
        val realSalt = salt ?: ByteArray(HASH_LEN)
        val prk = Hashes.hmacSha256(realSalt, ikm) // extract

        // expand
        val n = (len + HASH_LEN - 1) / HASH_LEN
        require(n <= 255) { "hkdf: requested length too long" }
        val okm = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            val input = Bytes.concat(t, info, byteArrayOf(i.toByte()))
            t = Hashes.hmacSha256(prk, input)
            val take = minOf(HASH_LEN, len - pos)
            System.arraycopy(t, 0, okm, pos, take)
            pos += take
        }
        return okm
    }
}
