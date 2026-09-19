package fin.android.crypto

import java.security.SecureRandom

/**
 * Entity id generator matching domain.NewID: a 14-byte buffer of
 * `uint48_be(unixMillis low 6 bytes) ‖ rand[8]`, encoded as Crockford base32 (lowercase, no
 * padding) → 23 characters. The time prefix makes ids lexicographically sortable by creation
 * time; the 64 random bits make them collision-free across machines.
 *
 * The sequence is also **monotonic within the process**, ULID-style (FORMAT.md 4.2). A
 * millisecond is far too coarse for a burst - an import or a bulk entry mints many records
 * inside one tick - and transactions are replayed in `(date, id)` order, so a fresh random
 * tail each time would draw their relative order at random and then freeze it in the ledger,
 * moving average-cost bases and everything computed from them. While the clock has not passed
 * the last id's millisecond (or steps backwards, as a time correction does), the generator
 * reuses that millisecond and increments the 8-byte tail by one instead of drawing a new one;
 * an all-ones tail carries into the next millisecond with a fresh tail.
 *
 * The state is process-local and nothing records it: ids stay opaque and no reader may rely on
 * the property. Length, alphabet, prefix and wire form are unchanged.
 */
object Ids {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz" // Crockford, excludes i l o u
    private val rng = SecureRandom()

    /** The last id handed out, as its raw 14 bytes; null until the first call. */
    private var last: ByteArray? = null

    fun newId(): String = mint(System.currentTimeMillis())

    /**
     * Mints the next id for one wall-clock millisecond, monotonically. Internal so tests can
     * drive the clock, backwards included; production always reads the real one.
     */
    @Synchronized
    internal fun mint(nowMillis: Long): String {
        val rnd = ByteArray(8)
        rng.nextBytes(rnd)
        var raw = rawId(nowMillis, rnd)
        val prev = last
        if (prev != null && !prefixIsAfter(raw, prev)) raw = nextAfter(prev)
        last = raw
        return crockford(raw)
    }

    /** The pure encoder, with no monotonic state: same bytes in, same id out. */
    fun newId(nowMillis: Long, rnd: ByteArray): String = crockford(rawId(nowMillis, rnd))

    private fun rawId(nowMillis: Long, rnd: ByteArray): ByteArray {
        require(rnd.size == 8) { "rnd must be 8 bytes" }
        val raw = ByteArray(14)
        val full = Bytes.uint64be(nowMillis)        // 8 big-endian bytes
        System.arraycopy(full, 2, raw, 0, 6)         // keep the low 6 bytes (48 bits)
        System.arraycopy(rnd, 0, raw, 6, 8)
        return raw
    }

    /** Whether a's 48-bit timestamp prefix is strictly after b's, compared unsigned. */
    private fun prefixIsAfter(a: ByteArray, b: ByteArray): Boolean {
        for (i in 0 until 6) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The smallest id strictly greater than [prev]: its 8-byte tail incremented by one, same
     * millisecond prefix. An all-ones tail carries into the prefix - the next millisecond,
     * with a fresh random tail - which takes 2^64 ids inside one tick to reach.
     */
    private fun nextAfter(prev: ByteArray): ByteArray {
        val next = prev.copyOf()
        for (i in 13 downTo 6) {
            val b = (next[i].toInt() and 0xff) + 1
            next[i] = b.toByte()
            if (b <= 0xff) return next // no carry out of this byte: done
        }
        // The tail wrapped to zero: move to the next millisecond with a fresh tail.
        val rnd = ByteArray(8)
        rng.nextBytes(rnd)
        return rawId(millisOf(prev) + 1, rnd)
    }

    /** Reads back the 48-bit timestamp prefix of a raw id. */
    private fun millisOf(raw: ByteArray): Long {
        var ms = 0L
        for (i in 0 until 6) ms = (ms shl 8) or (raw[i].toLong() and 0xff)
        return ms
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
