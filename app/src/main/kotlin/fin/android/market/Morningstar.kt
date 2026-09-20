package fin.android.market

import fin.android.domain.PricePoint
import fin.android.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fetches daily NAVs for funds identified by ISIN. It first resolves the ISIN to a Morningstar
 * "0P…" id via Boursorama's AJAX search, then downloads the COMPACTJSON timeseries from
 * tools.morningstar.fr. Currency is left null because Morningstar's API doesn't disclose it. Ported
 * faithfully from the Go provider. Returns null when the ISIN is missing, Boursorama yields no 0P…
 * link, or the series is empty.
 */
class Morningstar(
    private val base: String = "https://tools.morningstar.fr",
    private val boursoBase: String = "https://www.boursorama.com",
) : Provider {

    override val name: String = "morningstar"

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val isin = ref.isin?.takeIf { it.isNotEmpty() } ?: return null
        val msID = resolveViaBoursorama(isin) ?: return null
        return fetchNav(msID, from)
    }

    /** Queries Boursorama's AJAX search for an ISIN and scrapes the Morningstar "0P…" id. */
    private fun resolveViaBoursorama(isin: String): String? {
        val url = Http.url("$boursoBase/recherche/ajax", "query" to isin)
        val body = get(url, mapOf("X-Requested-With" to "XMLHttpRequest")) ?: return null
        return MS_ID_RE.find(body)?.groupValues?.get(1)
    }

    /** Downloads the daily NAV series for a Morningstar id (COMPACTJSON: `[[epochMillis, value], …]`). */
    private fun fetchNav(msID: String, from: LocalDate): DailyData? {
        val url = Http.url(
            "$base/api/rest.svc/timeseries_price/$TOKEN",
            "id" to msID,
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
        // Currency intentionally null - Morningstar doesn't disclose it.
        return DailyData(currency = null, closes = closes)
    }

    /** GET with a browser User-Agent, the given extra headers, and one retry on 429/5xx; null on failure. */
    private fun get(url: String, extraHeaders: Map<String, String>): String? =
        Http.send(url, headers = mapOf("User-Agent" to Http.USER_AGENT) + extraHeaders)
            .bodyIf { it == 200 }

    companion object {
        // Extracts the Morningstar fund id (0P…) from a Boursorama search result fragment.
        private val MS_ID_RE = Regex("""/bourse/(?:opcvm|trackers)/cours/(0P[0-9A-Za-z]+)/""")
        // The view id embedded in Morningstar's public chart pages; stable for years.
        private const val TOKEN = "ok91jeenoo"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
