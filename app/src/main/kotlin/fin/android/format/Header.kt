package fin.android.format

import fin.android.crypto.B64
import fin.android.crypto.Hashes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class HeaderDto(
    val fmt: String,
    val v: Int,
    val kdf: String,
    val t: Int,
    val m: Int,
    val p: Int,
    val salt: String,
    val id: String,
)

/**
 * Parsed, validated clear header (line 1). [hdrHash] = SHA-256 over the literal on-disk header
 * line bytes; it is the AAD prefix for every record and the head trailer.
 */
class Header private constructor(
    val v: Int,
    val t: Int,
    val m: Int,
    val p: Int,
    val salt: ByteArray,
    val id: ByteArray,
    val rawLine: String,
    val hdrHash: ByteArray,
) {
    companion object {
        const val FMT = "finador-ledger"
        const val VERSION = 3
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses and validates the header. Throws [UnsupportedFormatException] for an unknown
         * version, and [BadPasswordOrCorruptException] for malformed JSON or out-of-bounds KDF
         * parameters (the header is unauthenticated; strict bounds stop a forged header from
         * triggering a memory bomb before key derivation).
         */
        fun parse(rawLine: String): Header {
            val dto = try {
                json.decodeFromString(HeaderDto.serializer(), rawLine)
            } catch (e: Exception) {
                throw BadPasswordOrCorruptException(e)
            }
            if (dto.fmt != FMT) throw BadPasswordOrCorruptException()
            if (dto.v != VERSION) throw UnsupportedFormatException("unsupported format version: ${dto.v}")
            if (dto.kdf != "argon2id") throw BadPasswordOrCorruptException()

            val salt = try { B64.decode(dto.salt) } catch (e: Exception) { throw BadPasswordOrCorruptException(e) }
            val id = try { B64.decode(dto.id) } catch (e: Exception) { throw BadPasswordOrCorruptException(e) }

            val ok = dto.t in 1..16 &&
                dto.m in 8..1_048_576 &&
                dto.p in 1..16 &&
                salt.size == 16 &&
                id.size == 16
            if (!ok) throw BadPasswordOrCorruptException()

            val hash = Hashes.sha256(rawLine.toByteArray(Charsets.UTF_8))
            return Header(dto.v, dto.t, dto.m, dto.p, salt, id, rawLine, hash)
        }
    }
}
