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
    private val cookieUrl: String = "https://fc.yahoo.com/",
) : Provider {

    override val name: String = "yahoo"

    /** Yahoo's quote-API credentials, kept for the life of this provider. */
    private data class Auth(val cookie: String, val crumb: String)

    private var auth: Auth? = null

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val symbol = ref.symbol?.takeIf { it.isNotEmpty() } ?: return null // a ticker provider needs a symbol
        val r = chart(symbol, from)?.chart?.result?.firstOrNull() ?: return null
        val dividends = (r.events?.dividends?.values ?: emptyList())
            .map { DividendEvent(dateOf(it.date), it.amount) }
            .sortedBy { it.exDate }
        return DailyData(currency = r.meta?.currency, closes = closesOf(r), dividends = dividends)
    }

    /**
     * Returns the live regular-market price of each symbol, in one batched call per 50 symbols.
     * Symbols Yahoo does not answer for are simply absent; the whole call degrades to an empty map
     * rather than throwing, so a quote outage never breaks a refresh - it only leaves the daily
     * closes in charge.
     *
     * The quote API, unlike the chart API, needs a cookie + crumb pair. It is fetched once and
     * renewed once on the 401/403 an expired crumb produces.
     */
    fun quotes(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Quote>()
        for (chunk in symbols.distinct().chunked(QUOTE_BATCH_MAX)) {
            var auth = auth() ?: return out
            var (code, body) = quoteBody(chunk, auth)
            // Only 401/403 means the crumb went stale. Anything else - a 429 above all - must NOT
            // trigger a renewal: throwing three more requests at a host that is already throttling
            // is how a refresh turns into a ban, which is the very lag this pass exists to fix.
            if (body == null && (code == 401 || code == 403)) {
                this.auth = null
                auth = auth() ?: return out
                quoteBody(chunk, auth).let { (_, retried) -> body = retried }
            }
            parseQuotes(body ?: continue, out)
        }
        return out
    }

    private fun parseQuotes(body: String, into: MutableMap<String, Quote>) {
        val results = try {
            json.decodeFromString(QuoteResponse.serializer(), body).quoteResponse.result.orEmpty()
        } catch (_: Exception) {
            return
        }
        for (r in results) {
            val price = r.regularMarketPrice ?: continue
            // A price with no timestamp is not a quote: dated at the Unix epoch it splices a 1970
            // point into the cached series, where nothing ever removes it. Yahoo omits the field on
            // some halted and OTC lines.
            val time = r.regularMarketTime ?: continue
            if (price <= 0 || time <= 0) continue
            into[r.symbol] = Quote(r.symbol, price, time, r.currency)
        }
    }

    /** The v7 quote call for one chunk, as (HTTP status, body); the body is null on any failure. */
    private fun quoteBody(symbols: List<String>, auth: Auth): Pair<Int, String?> {
        val url = "$baseUrl/v7/finance/quote".toHttpUrl().newBuilder()
            .addQueryParameter("symbols", symbols.joinToString(","))
            .addQueryParameter("crumb", auth.crumb)
            .build()
        return fetch(url.toString(), auth.cookie)
    }

    /**
     * The cached cookie + crumb pair. Kept for the life of this provider, which is why callers
     * should hold on to one [Yahoo] rather than build a new one per refresh: the pair costs two
     * extra requests, and Yahoo counts them.
     */
    private fun auth(): Auth? {
        auth?.let { return it }
        val cookie = cookie() ?: return null
        val crumb = get("$baseUrl/v1/test/getcrumb", cookie)?.trim()
        if (crumb.isNullOrEmpty()) return null
        return Auth(cookie, crumb).also { auth = it }
    }

    /** Bootstraps Yahoo's consent cookie. The host answers 404 - the cookie is the point, not the body. */
    private fun cookie(): String? {
        return try {
            val req = Request.Builder().url(cookieUrl).header("User-Agent", Http.USER_AGENT).get().build()
            http.newCall(req).execute().use { resp ->
                resp.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
                    .ifEmpty { return null }
                    .joinToString("; ")
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Quotes `"{ccy}USD=X"` and returns its close series - the value of one unit of [ccy] in USD. */
    fun fxToUsd(ccy: String, from: LocalDate): PriceSeries? {
        val r = chart("${ccy}USD=X", from)?.chart?.result?.firstOrNull() ?: return null
        val points = closesOf(r)
        if (points.isEmpty()) return null
        return PriceSeries(points)
    }

    /** Pairs the result's timestamps with its close values, skipping holidays (null closes). */
    private fun closesOf(r: ChartResponse.Result): List<PricePoint> {
        val quoteCloses = r.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        val timestamps = r.timestamp ?: return emptyList()
        val closes = mutableListOf<PricePoint>()
        for (i in timestamps.indices) {
            if (i >= quoteCloses.size) break
            val c = quoteCloses[i] ?: continue // holiday or missing close
            closes.add(PricePoint(dateOf(timestamps[i]), c))
        }
        return closes
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
    private fun get(url: String, cookie: String? = null): String? = fetch(url, cookie).second

    /**
     * [get], but reporting the HTTP status alongside the body, so a caller can tell an expired
     * credential (401/403, worth renewing) from a throttled host (429, worth backing off). The
     * status is 0 when the call never got an answer.
     */
    private fun fetch(url: String, cookie: String? = null): Pair<Int, String?> {
        var status = 0
        repeat(2) { attempt ->
            try {
                val builder = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).get()
                if (cookie != null) builder.header("Cookie", cookie)
                val req = builder.build()
                http.newCall(req).execute().use { resp ->
                    status = resp.code
                    val retriable = resp.code == 429 || resp.code >= 500
                    if (retriable && attempt == 0) return@repeat
                    if (resp.code != 200) return status to null
                    return status to resp.body.string()
                }
            } catch (_: Exception) {
                if (attempt == 0) return@repeat
                return status to null
            }
        }
        return status to null
    }

    private fun dateOf(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Yahoo accepts far more per quote call; chunking just keeps URLs short. */
        private const val QUOTE_BATCH_MAX = 50
    }
}

@Serializable
private data class QuoteResponse(val quoteResponse: Body) {
    @Serializable
    data class Body(val result: List<Result>? = null)

    @Serializable
    data class Result(
        val symbol: String,
        val currency: String? = null,
        val regularMarketPrice: Double? = null,
        val regularMarketTime: Long? = null,
    )
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
