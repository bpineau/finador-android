package fin.android.market

import fin.android.crypto.AesGcm
import fin.android.crypto.B64
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The encrypted market-quote sidecar (FINCACHE2, FORMAT.md §7). On-disk layout:
 *
 * ```
 * "FINCACHE2"(9 ascii) ‖ nonce[12] ‖ AES-256-GCM( gzip( JSON(MarketData) ), AAD="FINCACHE2" )
 * ```
 *
 * The cache is fully regenerable: a missing, unreadable or wrong-magic file is never an error -
 * [read] returns null and a refresh rebuilds it. The JSON shape matches the Go implementation byte
 * for byte (`{"d","c"}` points, `{"exDate","amount"}` dividends, dates as civil-day `YYYY-MM-DD`
 * strings) so a ledger's cache is portable across implementations. The Android cacheDir wiring is
 * kept out of this object: callers pass a [File], which keeps it host-JVM testable.
 */
object CacheSidecar {
    private const val MAGIC = "FINCACHE2"
    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The sidecar filename for a header [id]: base64-RawURL of the id, then `.cache`. */
    fun cacheFileName(id: ByteArray): String = B64.encodeUrlNoPad(id) + ".cache"

    /** Reads and decrypts [file] under [keyCache]; null if missing, unreadable, or wrong magic. */
    fun read(file: File, keyCache: ByteArray): MarketData? {
        return try {
            val raw = file.readBytes()
            if (raw.size < MAGIC_BYTES.size + AesGcm.NONCE_LEN + AesGcm.TAG_LEN) return null
            if (!raw.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) return null
            val nonce = raw.copyOfRange(MAGIC_BYTES.size, MAGIC_BYTES.size + AesGcm.NONCE_LEN)
            val ct = raw.copyOfRange(MAGIC_BYTES.size + AesGcm.NONCE_LEN, raw.size)
            val plain = AesGcm.open(keyCache, nonce, ct, MAGIC_BYTES)
            val jsonBytes = GZIPInputStream(plain.inputStream()).use { it.readBytes() }
            val dto = json.decodeFromString(MarketDataDto.serializer(), jsonBytes.toString(Charsets.UTF_8))
            dto.toDomain()
        } catch (_: Exception) {
            null // regenerable: never surface read failures to the caller
        }
    }

    /** Encrypts [data] under [keyCache] and writes it to [file] in FINCACHE2 format. */
    fun write(file: File, keyCache: ByteArray, data: MarketData) {
        val jsonBytes = json.encodeToString(MarketDataDto.serializer(), MarketDataDto.from(data))
            .toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(jsonBytes) }
        val nonce = ByteArray(AesGcm.NONCE_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val sealed = AesGcm.seal(keyCache, nonce, gz.toByteArray(), MAGIC_BYTES)
        file.parentFile?.mkdirs()
        file.writeBytes(MAGIC_BYTES + nonce + sealed)
    }
}

// --- JSON DTOs: keys must match the Go domain types exactly. ---

@Serializable
private data class PricePointDto(
    @SerialName("d") val d: String,
    @SerialName("c") val c: Double,
) {
    fun toDomain() = PricePoint(LocalDate.parse(d), c)
    companion object {
        fun from(p: PricePoint) = PricePointDto(p.date.toString(), p.close)
    }
}

@Serializable
private data class PriceSeriesDto(
    val points: List<PricePointDto> = emptyList(),
    val fetchedAt: String? = null,
) {
    fun toDomain() = PriceSeries(points.map { it.toDomain() }, fetchedAt?.let { LocalDate.parse(it) })
    companion object {
        fun from(s: PriceSeries) = PriceSeriesDto(s.points.map { PricePointDto.from(it) }, s.fetchedAt?.toString())
    }
}

@Serializable
private data class DividendEventDto(
    val exDate: String,
    val amount: Double,
) {
    fun toDomain() = DividendEvent(LocalDate.parse(exDate), amount)
    companion object {
        fun from(e: DividendEvent) = DividendEventDto(e.exDate.toString(), e.amount)
    }
}

@Serializable
private data class MarketDataDto(
    val prices: Map<String, PriceSeriesDto> = emptyMap(),
    val fx: Map<String, PriceSeriesDto> = emptyMap(),
    val dividends: Map<String, List<DividendEventDto>> = emptyMap(),
) {
    fun toDomain() = MarketData(
        prices = prices.mapValues { it.value.toDomain() },
        fx = fx.mapValues { it.value.toDomain() },
        dividends = dividends.mapValues { it.value.map { d -> d.toDomain() } },
    )
    companion object {
        fun from(m: MarketData) = MarketDataDto(
            prices = m.prices.mapValues { PriceSeriesDto.from(it.value) },
            fx = m.fx.mapValues { PriceSeriesDto.from(it.value) },
            dividends = m.dividends.mapValues { it.value.map { d -> DividendEventDto.from(d) } },
        )
    }
}
