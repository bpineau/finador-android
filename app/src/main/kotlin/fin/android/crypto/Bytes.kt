package fin.android.crypto

import java.util.Base64

/** Low-level byte helpers shared across the crypto and format layers. */
object Bytes {
    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** 8-byte big-endian encoding of an unsigned 64-bit value. */
    fun uint64be(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (8 * (7 - i))).toByte()
        return out
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
        return out
    }
}

/** Base64 variants used by the format: standard padded (lines, salt, id, head) and
 *  URL-safe no-pad (sidecar cache filename). */
object B64 {
    private val stdEnc: Base64.Encoder = Base64.getEncoder()
    private val stdDec: Base64.Decoder = Base64.getDecoder()
    private val urlEncNoPad: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(b: ByteArray): String = stdEnc.encodeToString(b)
    /** Decodes standard base64, tolerating embedded whitespace/newlines (GitHub wraps at 60 cols). */
    fun decode(s: String): ByteArray = stdDec.decode(s.filterNot { it == '\n' || it == '\r' || it == ' ' })
    fun encodeUrlNoPad(b: ByteArray): String = urlEncNoPad.encodeToString(b)
}
