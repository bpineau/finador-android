package fin.android.crypto

import java.security.SecureRandom

/**
 * Entity id generator matching domain.NewID: a 14-byte buffer of
 * `uint48_be(unixMillis low 6 bytes) ‖ rand[8]`, encoded as Crockford base32 (lowercase, no
 * padding) → 23 characters. The time prefix makes ids lexicographically sortable by creation
 * time; the 64 random bits make them collision-free across machines.
 */
object Ids {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz" // Crockford, excludes i l o u
    private val rng = SecureRandom()

    fun newId(): String {
        val rnd = ByteArray(8)
        rng.nextBytes(rnd)
        return newId(System.currentTimeMillis(), rnd)
    }

    fun newId(nowMillis: Long, rnd: ByteArray): String {
        require(rnd.size == 8) { "rnd must be 8 bytes" }
        val raw = ByteArray(14)
        val full = Bytes.uint64be(nowMillis)        // 8 big-endian bytes
        System.arraycopy(full, 2, raw, 0, 6)         // keep the low 6 bytes (48 bits)
        System.arraycopy(rnd, 0, raw, 6, 8)
        return crockford(raw)
    }

    private fun crockford(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                val idx = (buffer ushr (bits - 5)) and 0x1f
                sb.append(ALPHABET[idx])
                bits -= 5
            }
        }
        if (bits > 0) {
            val idx = (buffer shl (5 - bits)) and 0x1f
            sb.append(ALPHABET[idx])
        }
        return sb.toString()
    }
}
