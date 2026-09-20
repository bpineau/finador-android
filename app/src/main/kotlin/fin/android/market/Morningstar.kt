package fin.android.market

import fin.android.domain.PricePoint
import fin.android.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily NAVs for funds identified by ISIN, from Morningstar's public API: the ISIN is resolved to a
 * Morningstar security id through the fund SCREENER behind Morningstar's own fund pages, then the
 * COMPACTJSON timeseries is downloaded for that id. Ported from the Go reference's
 * `pkg/marketdata/morningstar.go`.
 *
 * Returns null when the ref carries no ISIN, when the screener does not know it, or when the series
 * comes back empty; the [MultiSource] chain then falls through to the next provider. This one is
 * the last link, so a null here is the end of the road for a fund the others do not cover.
 *
 * Three things about this endpoint are worth knowing before touching it.
 *
 * 1. **The host moved.** This provider used to call `tools.morningstar.fr`, whose name stopped
 *    resolving in 2026 (its CNAME target lost its address record, for everyone). `lt.morningstar.com`
 *    is what the Go reference uses and what answers today.
 * 2. **There are two view tokens, and they are not interchangeable.** [TIMESERIES_TOKEN] is the one
 *    embedded in the public chart pages, [SCREENER_TOKEN] the one the fund-search pages use; the
 *    timeseries token is refused by the screener.
 * 3. **The id carries a suffix.** The timeseries service reads its `id` as a bracket-separated
 *    tuple and answers an EMPTY array, with HTTP 200, for a bare exchange-traded id. Only the
 *    presence of the two trailing fields matters, not their content, and appending [ID_SUFFIX] to
 *    an open-end id is harmless. Losing it is what once silently emptied this source in the Go
 *    reference.
 *
 * The resolution also reports the quote currency ([CURRENCY_PREFIX] padding removed), which the
 * timeseries API withholds; a venue sub-unit keeps its own spelling so [Units] can remove it.
 */
class Morningstar(
    private val base: String = "https://lt.morningstar.com",
) : Provider {

    override val name: String = "morningstar"

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val isin = ref.isin?.takeIf { it.isNotEmpty() } ?: return null
        val hit = search(isin) ?: return null
        return fetchNav(hit, from)
    }

    /** One screener row that survived: the security id to quote and the currency it quotes in. */
    private data class Hit(val secId: String, val currency: String?)

    /**
     * Resolves [query] (an ISIN here) through Morningstar's fund screener, over the open-end and
     * exchange-traded universes together: a share class sits in exactly one of them and the caller
     * never knows which.
     *
     * A row whose ISIN IS the query wins over a mere full-text hit, so an ISIN lookup never adopts
     * a same-family class quoted in another currency.
     */
    private fun search(query: String): Hit? {
        val url = Http.url(
            "$base/api/rest.svc/$SCREENER_TOKEN/security/screener",
            "page" to "1",
            "pageSize" to "10",
            "outputType" to "json",
            "universeIds" to UNIVERSES,
            "securityDataPoints" to DATA_POINTS,
            "term" to query,
        )
        val body = get(url, emptyMap()) ?: return null
        val page = try {
            json.decodeFromString(ScreenerPage.serializer(), body)
        } catch (_: Exception) {
            return null
        }
        val rows = page.rows.filter { it.secId.isNotEmpty() }
        val row = rows.firstOrNull { it.isin.equals(query, ignoreCase = true) } ?: rows.firstOrNull()
        return row?.let { Hit(it.secId, currencyOf(it.currencyId)) }
    }

    /** Downloads the daily NAV series for a Morningstar id (COMPACTJSON: `[[epochMillis, value], …]`). */
    private fun fetchNav(hit: Hit, from: LocalDate): DailyData? {
        val url = Http.url(
            "$base/api/rest.svc/timeseries_price/$TIMESERIES_TOKEN",
            "id" to hit.secId + ID_SUFFIX,
            "idtype" to "Morningstar",
            "frequency" to "daily",
            "startDate" to from.toString(),
            "outputType" to "COMPACTJSON",
        )
        val body = get(url, emptyMap()) ?: return null
        val rows = try {
            json.decodeFromString(JsonArray.serializer(), body)
        } catch (_: Exception) {
            return null // errors come back as XML/HTML
        }
        if (rows.isEmpty()) return null
        val closes = mutableListOf<PricePoint>()
        var prev: LocalDate? = null
        for (row in rows) {
            val arr = row as? JsonArray ?: continue
            if (arr.size < 2) continue
            val value = try {
                arr[1].jsonPrimitive.double
            } catch (_: Exception) {
                continue
            }
            if (value <= 0) continue
            val millis = try {
                arr[0].jsonPrimitive.double.toLong()
            } catch (_: Exception) {
                continue
            }
            val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            if (day.isBefore(from)) continue
            // Keep dates strictly increasing.
            if (prev != null && !prev.isBefore(day)) continue
            closes.add(PricePoint(day, value))
            prev = day
        }
        if (closes.isEmpty()) return null
        return Units.normalize(DailyData(currency = hit.currency, closes = closes))
    }

    /** GET with a browser User-Agent, the given extra headers, and one retry on 429/5xx; null on failure. */
    private fun get(url: String, extraHeaders: Map<String, String>): String? =
        Http.send(url, headers = mapOf("User-Agent" to Http.USER_AGENT) + extraHeaders)
            .bodyIf { it == 200 }

    @Serializable
    private data class ScreenerPage(val rows: List<ScreenerRow> = emptyList())

    @Serializable
    private data class ScreenerRow(
        @SerialName("SecId") val secId: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("isin") val isin: String = "",
        @SerialName("currencyId") val currencyId: String = "",
    )

    companion object {
        /** The view id embedded in Morningstar's public chart pages; stable for years. */
        private const val TIMESERIES_TOKEN = "ok91jeenoo"

        /** The view id of the screener behind the fund-search pages. A second, independent token. */
        private const val SCREENER_TOKEN = "klr5zyak8x"

        /** Open-end funds and exchange-traded ones, searched together. */
        private const val UNIVERSES = "FOALL\$\$ALL|ETALL\$\$ALL"

        /** The four columns this provider reads; asking for fewer is not cheaper and asking for more is noise. */
        private const val DATA_POINTS = "SecId|Name|isin|currencyId"

        /** Disambiguates the id for the timeseries API. See the class doc, point 3. */
        private const val ID_SUFFIX = "]2]1]"

        /** The padding the screener wraps a currency in: `CU${'$'}${'$'}${'$'}${'$'}${'$'}EUR`. */
        private const val CURRENCY_PREFIX = '$'

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The ISO code a screener `currencyId` names, or null when it names nothing readable - which
         * the fetch path reads as "unknown" rather than as a mismatch.
         *
         * A venue sub-unit keeps its own spelling: uppercasing `GBp` would turn pence into pounds
         * and lose the hundredth [Units] still has to remove.
         */
        internal fun currencyOf(id: String): String? {
            val code = id.substringAfterLast(CURRENCY_PREFIX)
            if (code.length != 3) return null
            return if (Units.isMinor(code)) code else code.uppercase()
        }
    }
}
