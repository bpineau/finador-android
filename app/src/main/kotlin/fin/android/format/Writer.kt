package fin.android.format

import fin.android.crypto.AesGcm
import fin.android.crypto.B64
import fin.android.crypto.Bytes
import java.security.SecureRandom

private val HEAD_LABEL_W = "finador-head".toByteArray(Charsets.US_ASCII)

/**
 * Append-only writer. New records continue the hash chain from the last existing tag; existing
 * record lines are re-emitted verbatim by [serialize], so a small logical change is a small diff
 * on disk (git-friendly). The head trailer is re-sealed (fresh nonce) on every serialize.
 */
internal object Writer {
    private val rng = SecureRandom()

    /** Returns a new entry list = [entries] followed by freshly sealed records for [envs]. */
    fun append(header: Header, keys: Keys, entries: List<Entry>, envs: List<Envelope>): List<Entry> {
        val out = ArrayList(entries)
        var prevTag = entries.lastOrNull()?.tag ?: ByteArray(AesGcm.TAG_LEN)
        var seq = entries.size.toLong()
        for (env in envs) {
            seq++
            val pt = wireJson.encodeToString(Envelope.serializer(), env).toByteArray(Charsets.UTF_8)
            val aad = Bytes.concat(header.hdrHash, Bytes.uint64be(seq), prevTag)
            val (line, tag) = seal(keys.log, pt, aad)
            out.add(Entry(line, tag, env))
            prevTag = tag
        }
        return out
    }

    /** Serializes header + verbatim record lines + a freshly sealed head trailer, each `\n`-terminated. */
    fun serialize(header: Header, keys: Keys, entries: List<Entry>): ByteArray {
        val lastTag = entries.lastOrNull()?.tag ?: ByteArray(AesGcm.TAG_LEN)
        val headPt = wireJson.encodeToString(HeadDto.serializer(), HeadDto(entries.size, B64.encode(lastTag)))
            .toByteArray(Charsets.UTF_8)
        val headAad = Bytes.concat(header.hdrHash, HEAD_LABEL_W, Bytes.uint64be(entries.size.toLong()))
        val (headLine, _) = seal(keys.log, headPt, headAad)

        val sb = StringBuilder(header.rawLine.length + entries.sumOf { it.line.length + 1 } + headLine.length + 8)
        sb.append(header.rawLine).append('\n')
        for (e in entries) sb.append(e.line).append('\n')
        sb.append(headLine).append('\n')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun seal(keyLog: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<String, ByteArray> {
        val nonce = ByteArray(AesGcm.NONCE_LEN).also { rng.nextBytes(it) }
        val ct = AesGcm.seal(keyLog, nonce, plaintext, aad)
        val tag = ct.copyOfRange(ct.size - AesGcm.TAG_LEN, ct.size)
        return B64.encode(Bytes.concat(nonce, ct)) to tag
    }
}
