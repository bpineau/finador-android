package fin.android.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared JSON codec. Unknown fields of a known kind are tolerated (forward-compatible). */
internal val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** The 12 ASCII bytes mixed into the head/trailer AAD — shared by the reader and the writer so they
 *  can never drift on the trailer authentication. */
internal val HEAD_LABEL: ByteArray = "finador-head".toByteArray(Charsets.US_ASCII)

/** Record envelope: kind, creation timestamp (RFC3339Nano UTC), and the kind-specific payload. */
@Serializable
internal data class Envelope(val k: String, val ts: String, val d: JsonObject)

@Serializable
internal data class MoneyDto(val amount: String, val ccy: String)

@Serializable
internal data class IdRefDto(val id: String)

@Serializable
internal data class CfgDto(val key: String, val value: String)

@Serializable
internal data class AcctDto(
    val id: String,
    val name: String,
    val ccy: String,
    val tax: String,
    val aliases: List<String> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val id: String,
    val kind: String,
    val name: String,
    val ticker: String? = null,
    val isin: String? = null,
    val aliases: List<String> = emptyList(),
    val ccy: String,
    val group: String? = null,
    val withholding: Double? = null,
)

@Serializable
internal data class TxDto(
    val id: String,
    val date: String,
    val account: String,
    val asset: String? = null,
    val kind: String,
    val qty: String,
    val amount: MoneyDto,
    val note: String? = null,
    val importHash: String? = null,
)

@Serializable
internal data class LabelDto(
    val id: String,
    val account: String,
    val asset: String,
    val name: String,
)

@Serializable
internal data class HeadDto(val count: Int, val head: String)
