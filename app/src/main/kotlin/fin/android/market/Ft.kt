package fin.android.market

import fin.android.domain.PricePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Quotes mutual funds and SICAVs by ISIN or ticker through the Financial Times market-data
 * endpoints, which cover many FR/LU/US funds Yahoo lacks (or throttles). Ported faithfully from the
 * Go FT provider: resolve the instrument through FT search (ISIN first, then ticker), then download
 * its NAV series. Returns null when neither identifier is given or FT doesn't list it.
 */
class Ft(
    private val baseUrl: String = "https://markets.ft.com",
    private val http: OkHttpClient = Http.defaultClient(),
) : Provider {

    override val name: String = "ft"

    private data class Resolution(val xid: String, val name: String, val currency: String)

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val queries = buildList {
            ref.isin?.takeIf { it.isNotEmpty() }?.let { add(it) }
            ref.symbol?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        if (queries.isEmpty()) return null
        for (q in queries) {
            val res = search(q) ?: continue
            val data = series(res, from)
            if (data != null && data.closes.isNotEmpty()) return data
        }
        return null
    }

    /** Resolves a query (ISIN/ticker/name) through the FT securities search. */
    private fun search(query: String): Resolution? {
        val url = "$baseUrl/data/searchapi/searchsecurities".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).build()
        val body = get(url.toString()) ?: return null
        val resp = try {
            json.decodeFromString(SearchResponse.serializer(), body)
        } catch (_: Exception) {
            return null
        }
        val secs = resp.data?.security ?: return null
        // Prefer a listing not quoted in pence (GBX/GBp); fall back to any match with an xid.
        var best = secs.firstOrNull { it.xid?.isNotEmpty() == true && symbolCurrency(it.symbol).let { c -> c != "GBX" && c != "GBp" } }
            ?: secs.firstOrNull { it.xid?.isNotEmpty() == true }
            ?: return null // FT doesn't list this instrument: let the chain fall through
        return Resolution(best.xid!!, best.name ?: "", symbolCurrency(best.symbol))
    }

    /** Downloads the daily NAV close series for a resolved instrument from the FT chart API. */
    private fun series(res: Resolution, from: LocalDate): DailyData? {
        val days = maxOf(ChronoUnit.DAYS.between(from, LocalDate.now()).toInt() + 2, 2)
        val payload: JsonObject = buildJsonObject {
            put("days", days)
            put("dataPeriod", "Day")
            put("dataInterval", 1)
            put("timeServiceFormat", "JSON")
            put("returnDateType", "ISO8601")
            put("elements", buildJsonArray {
                add(buildJsonObject {
                    put("Type", "price")
                    put("Symbol", res.xid)
                })
            })
        }
        val body = post("$baseUrl/data/chartapi/series", payload.toString()) ?: return null
        val resp = try {
            json.decodeFromString(SeriesResponse.serializer(), body)
        } catch (_: Exception) {
            return null
        }
        val element = resp.elements?.firstOrNull() ?: return null
        val dates = resp.dates ?: return null
        val closes = element.componentSeries?.firstOrNull { it.type == "Close" }?.values ?: return null
        if (closes.size != dates.size) return null
        val currency = element.currency?.takeIf { it.isNotEmpty() } ?: res.currency
        val out = mutableListOf<PricePoint>()
        for (i in dates.indices) {
            val cl = closes[i] ?: continue
            if (cl <= 0) continue
            val day = try {
                LocalDateTime.parse(dates[i], ISO_NO_ZONE).toLocalDate()
            } catch (_: Exception) {
                continue
            }
            if (day.isBefore(from)) continue
            out.add(PricePoint(day, cl))
        }
        return DailyData(currency = currency, closes = out)
    }

    /** Currency = the last `:`-separated segment of an FT symbol like "LU0171310443:EUR" or "NTSG:GER:EUR". */
    private fun symbolCurrency(symbol: String?): String {
        if (symbol == null) return ""
        val parts = symbol.split(":")
        return if (parts.size < 2) "" else parts.last()
    }

    private fun get(url: String): String? = execute(Request.Builder().url(url).get())

    private fun post(url: String, body: String): String? =
        execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)))

    /** Executes with a browser User-Agent and one retry on 429/5xx; null on any IO/HTTP failure. */
    private fun execute(builder: Request.Builder): String? {
        repeat(2) { attempt ->
            try {
                val req = builder.header("User-Agent", Http.USER_AGENT).header("Accept", "application/json").build()
                http.newCall(req).execute().use { resp ->
                    val retriable = resp.code == 429 || resp.code >= 500
                    if (retriable && attempt == 0) return@repeat
                    if (resp.code != 200) return null
                    return resp.body?.string()
                }
            } catch (_: Exception) {
                if (attempt == 0) return@repeat
                return null
            }
        }
        return null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val JSON_MEDIA = "application/json".toMediaType()
        private val ISO_NO_ZONE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}

@Serializable
private data class SearchResponse(val data: Data? = null) {
    @Serializable data class Data(val security: List<Security>? = null)
    @Serializable data class Security(
        val name: String? = null,
        val symbol: String? = null,
        val xid: String? = null,
        val isPrimary: Boolean = false,
    )
}

@Serializable
private data class SeriesResponse(
    @kotlinx.serialization.SerialName("Dates") val dates: List<String>? = null,
    @kotlinx.serialization.SerialName("Elements") val elements: List<Element>? = null,
) {
    @Serializable data class Element(
        @kotlinx.serialization.SerialName("Currency") val currency: String? = null,
        @kotlinx.serialization.SerialName("ComponentSeries") val componentSeries: List<ComponentSeries>? = null,
    )
    @Serializable data class ComponentSeries(
        @kotlinx.serialization.SerialName("Type") val type: String? = null,
        @kotlinx.serialization.SerialName("Values") val values: List<Double?>? = null,
    )
}
