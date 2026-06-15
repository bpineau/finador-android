package fin.android.market

import fin.android.domain.DividendEvent
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The default provider: the unofficial but stable Yahoo Finance chart API. No key, no auth - just a
 * browser-looking User-Agent and one polite retry on 429/5xx.
 *
 * Timezone note: the Go implementation converts each timestamp in the exchange's local zone (via
 * embedded tzdata). The Android side intentionally does NOT bundle tzdata and uses UTC for the
 * civil-day conversion - a quote near midnight may land one day off relative to Go, which is harmless
 * for daily analytics (forward-fill smooths it).
 */
class Yahoo(
    private val baseUrl: String = "https://query1.finance.yahoo.com",
    private val http: OkHttpClient = Http.defaultClient(),
) : Provider {

    override val name: String = "yahoo"

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val symbol = ref.symbol?.takeIf { it.isNotEmpty() } ?: return null // a ticker provider needs a symbol
        val resp = chart(symbol, from) ?: return null
        val r = resp.chart.result?.firstOrNull() ?: return null

        val closes = mutableListOf<PricePoint>()
        val quoteCloses = r.indicators?.quote?.firstOrNull()?.close
        val timestamps = r.timestamp ?: emptyList()
        if (quoteCloses != null) {
            for (i in timestamps.indices) {
                if (i >= quoteCloses.size) break
                val c = quoteCloses[i] ?: continue // holiday or missing close
                closes.add(PricePoint(dateOf(timestamps[i]), c))
            }
        }
        val dividends = (r.events?.dividends?.values ?: emptyList())
            .map { DividendEvent(dateOf(it.date), it.amount) }
            .sortedBy { it.exDate }
        return DailyData(currency = r.meta?.currency, closes = closes, dividends = dividends)
    }

    /** Quotes `"{ccy}USD=X"` and returns its close series - the value of one unit of [ccy] in USD. */
    fun fxToUsd(ccy: String, from: LocalDate): PriceSeries? {
        val resp = chart("${ccy}USD=X", from) ?: return null
        val r = resp.chart.result?.firstOrNull() ?: return null
        val quoteCloses = r.indicators?.quote?.firstOrNull()?.close ?: return null
        val timestamps = r.timestamp ?: return null
        val points = mutableListOf<PricePoint>()
        for (i in timestamps.indices) {
            if (i >= quoteCloses.size) break
            val c = quoteCloses[i] ?: continue
            points.add(PricePoint(dateOf(timestamps[i]), c))
        }
        if (points.isEmpty()) return null
        return PriceSeries(points)
    }

    private fun chart(symbol: String, from: LocalDate): ChartResponse? {
        val period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val period2 = Instant.now().epochSecond + 86400
        val url = "$baseUrl/v8/finance/chart/$symbol".toHttpUrl().newBuilder()
            .addQueryParameter("period1", period1.toString())
            .addQueryParameter("period2", period2.toString())
            .addQueryParameter("interval", "1d")
            .addQueryParameter("events", "div")
            .build()
        val body = get(url.toString()) ?: return null
        return try {
            json.decodeFromString(ChartResponse.serializer(), body)
        } catch (_: Exception) {
            null
        }
    }

    /** GET with a browser User-Agent and one retry on 429/5xx; null on any IO/HTTP failure. */
    private fun get(url: String): String? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).get().build()
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

    private fun dateOf(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ChartResponse(val chart: Chart) {
    @Serializable
    data class Chart(val result: List<Result>? = null, val error: kotlinx.serialization.json.JsonElement? = null)

    @Serializable
    data class Result(
        val meta: Meta? = null,
        val timestamp: List<Long>? = null,
        val events: Events? = null,
        val indicators: Indicators? = null,
    )

    @Serializable
    data class Meta(val currency: String? = null)

    @Serializable
    data class Events(val dividends: Map<String, Dividend>? = null)

    @Serializable
    data class Dividend(val amount: Double, val date: Long)

    @Serializable
    data class Indicators(val quote: List<Quote>? = null)

    @Serializable
    data class Quote(@SerialName("close") val close: List<Double?>? = null)
}
