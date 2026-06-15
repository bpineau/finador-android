package fin.android.format

import fin.android.crypto.AesGcm
import fin.android.crypto.B64
import fin.android.crypto.Bytes

/** One persisted record: its verbatim base64 line, its GCM tag (for chaining), and its envelope. */
internal class Entry(val line: String, val tag: ByteArray, val env: Envelope)

internal class RawLedger(val header: Header, val keys: Keys, val entries: List<Entry>)

/**
 * Reads and authenticates the on-disk log: header → N records (chained via AAD) → head trailer.
 * Any cryptographic, base64 or JSON failure collapses to [BadPasswordOrCorruptException]; an
 * unknown version surfaces from [Header.parse] as [UnsupportedFormatException].
 */
internal object Log {
    fun open(bytes: ByteArray, passphrase: String): RawLedger {
        val trimmed = bytes.toString(Charsets.UTF_8).trimEnd('\n')
        if (trimmed.isEmpty()) throw BadPasswordOrCorruptException()
        val lines = trimmed.split('\n')
        if (lines.size < 2) throw BadPasswordOrCorruptException()

        val header = Header.parse(lines[0])
        val keys = deriveKeys(passphrase, header)
        val n = lines.size - 2

        try {
            val entries = ArrayList<Entry>(n)
            var prevTag = ByteArray(AesGcm.TAG_LEN)
            for (i in 1..n) {
                val raw = B64.decode(lines[i])
                if (raw.size < AesGcm.NONCE_LEN + AesGcm.TAG_LEN) throw BadPasswordOrCorruptException()
                val nonce = raw.copyOfRange(0, AesGcm.NONCE_LEN)
                val ctTag = raw.copyOfRange(AesGcm.NONCE_LEN, raw.size)
                val aad = Bytes.concat(header.hdrHash, Bytes.uint64be(i.toLong()), prevTag)
                val pt = AesGcm.open(keys.log, nonce, ctTag, aad)
                val tag = ctTag.copyOfRange(ctTag.size - AesGcm.TAG_LEN, ctTag.size)
                val env = wireJson.decodeFromString(Envelope.serializer(), pt.toString(Charsets.UTF_8))
                entries.add(Entry(lines[i], tag, env))
                prevTag = tag
            }

            val traw = B64.decode(lines[n + 1])
            if (traw.size < AesGcm.NONCE_LEN + AesGcm.TAG_LEN) throw BadPasswordOrCorruptException()
            val tNonce = traw.copyOfRange(0, AesGcm.NONCE_LEN)
            val tCtTag = traw.copyOfRange(AesGcm.NONCE_LEN, traw.size)
            val headAad = Bytes.concat(header.hdrHash, HEAD_LABEL, Bytes.uint64be(n.toLong()))
            val headPt = AesGcm.open(keys.log, tNonce, tCtTag, headAad)
            val head = wireJson.decodeFromString(HeadDto.serializer(), headPt.toString(Charsets.UTF_8))

            if (head.count != n) throw BadPasswordOrCorruptException()
            val lastTag = if (n == 0) ByteArray(AesGcm.TAG_LEN) else entries.last().tag
            if (!B64.decode(head.head).contentEquals(lastTag)) throw BadPasswordOrCorruptException()

            return RawLedger(header, keys, entries)
        } catch (e: BadPasswordOrCorruptException) {
            throw e
        } catch (e: Exception) {
            // AEADBadTagException, base64, JSON, bounds - all indistinguishable by design.
            throw BadPasswordOrCorruptException(e)
        }
    }
}
