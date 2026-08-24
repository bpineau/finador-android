package fin.android.market

import fin.android.domain.PricePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/**
 * One French employee-savings fund (FCPE) quoted from the airfund.io delivery API.
 *
 * Such a fund has no ISIN, no exchange listing and no coverage on any quote site, so this feed is
 * the only machine-readable NAV history there is. [shareCode] is the share-class code the
 * management company uses in place of an ISIN, and [ticker] is what the ledger's asset carries, so
 * the fund is recognised exactly like any other security.
 *
 * [proxy] is the nowcast proxy: a listed instrument, quoted in [proxyCcy], whose moves stand in for
 * the fund between its last published NAV and now (see [Nowcast]). It is chosen for TIMING rather
 * than for a perfect fee match: the NAV must be struck against the same session the proxy closes on.
 */
data class AirfundFund(
    val ticker: String,
    val shareCode: String,
    val proxy: String,
    val proxyCcy: String,
    val ccy: String,
    val name: String,
)

/**
 * The known FCPE share classes and their nowcast proxies. Both entries are measured in the Go
 * reference (`../pofo/docs/eres-fcpe-design.md`); the caveats worth knowing here:
 *
 * - `ERESMONDEM` strikes its NAV on the official NAVs of the two MSCI World ETFs it holds, which
 *   value New York at the New York close. Only a US-listed tracker shares that clock: URTH
 *   correlates 0.875 daily with the fund, where a Xetra or LSE line correlates 0.62 or less
 *   (they close before the US afternoon). Hence a NYSE Arca proxy rather than a European one.
 * - `ERES_DATADOG` is a single-stock fund whose NAV is struck on the NASDAQ OPENING price, not the
 *   close. A close-anchored estimate therefore carries the valuation day's open-to-close move as an
 *   offset until the next NAV lands: typically a percent, more on an earnings day. It also valued
 *   WEEKLY (Fridays) until 2026-07-13, and daily since.
 *
 * Both NAVs are published with a lag of about two business days, which is the whole reason the
 * nowcast exists.
 */
object AirfundFunds {
    val ALL: List<AirfundFund> = listOf(
        AirfundFund(
            ticker = "ERESMONDEM",
            shareCode = "990000135629",
            proxy = "URTH",
            proxyCcy = "USD",
            ccy = "EUR",
            name = "ERES Xtrackers Actions Monde, Part M",
        ),
        AirfundFund(
            ticker = "ERES_DATADOG",
            shareCode = "990000124099",
            proxy = "DDOG",
            proxyCcy = "USD",
            ccy = "EUR",
            name = "Actions Datadog, Part C",
        ),
    )

    private val index = ALL.associateBy { it.ticker }

    /** The fund an asset ticker names, or null when the ticker is not an FCPE this app knows. */
    fun byTicker(ticker: String?): AirfundFund? = ticker?.let { index[it.trim().uppercase()] }
}

/**
 * Quotes the FCPE share classes of [AirfundFunds] from the airfund.io delivery API, the service
 * behind the NAV chart and the "Exporter les VLs" button of the management company's fund page.
 *
 * One POST, no login, no cookie, no key. The API answers `201` (any 2xx is accepted), returns the
 * WHOLE history every time and knows no start date, so [daily] ignores its `from` argument: the
 * series is small (hundreds of NAVs) and a fresh install must chart the fund back to its launch.
 *
 * Offline, or when the call fails, the bundled NAV snapshot answers instead ([EmbeddedNavs]); when
 * both are available the live rows win on a shared date and the bundled rows fill the past. Any
 * failure returns null rather than throwing, so the [MultiSource] chain simply moves on.
 */
class Airfund(
    private val baseUrl: String = "https://core.communicate.airfund.io",
    private val http: OkHttpClient = Http.defaultClient(),
) : Provider {

    override val name: String = "airfund"

    override fun daily(ref: Ref, from: LocalDate): DailyData? {
        val fund = AirfundFunds.byTicker(ref.symbol) ?: return null // not covered: next provider
        val points = mergeNavs(EmbeddedNavs.of(fund.ticker), fetchNavs(fund).orEmpty())
        if (points.isEmpty()) return null
        return DailyData(currency = fund.ccy, closes = points)
    }

    /** The live NAV history of one share class; null on any IO, HTTP or parse failure. */
    internal fun fetchNavs(fund: AirfundFund): List<PricePoint>? {
        val payload = json.encodeToString(
            NavRequest.serializer(),
            NavRequest(sId = WIDGET_ID, isinCode = fund.shareCode),
        )
        val body = post("$baseUrl$CHART_PATH", payload) ?: return null
        return parseNavs(body)
    }

    /** POST with a browser User-Agent and one retry on 429/5xx; null on any failure. */
    private fun post(url: String, payload: String): String? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", Http.USER_AGENT)
                    .header("Accept", "application/json")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val retriable = resp.code == 429 || resp.code >= 500
                    if (retriable && attempt == 0) return@repeat
                    // This API answers a POST with 201, so the whole 2xx range is a success.
                    if (resp.code !in 200..299) return null
                    return resp.body.string()
                }
            } catch (_: Exception) {
                if (attempt == 0) return@repeat
                return null
            }
        }
        return null
    }

    companion object {
        /** The NAV-history endpoint of the delivery API (POST JSON in, JSON out). */
        private const val CHART_PATH = "/api/v1/navs-evolution-chart/data"

        /**
         * The id of the chart widget embedded on the management company's fund pages. It is the
         * SITE's, not the fund's: one id serves every fund of the site. The API answers 500 without
         * it, which is why it is not optional.
         */
        private const val WIDGET_ID = "41481ca4-919c-46c0-9ca1-41a880ff4e8e"

        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

        /**
         * Decodes the chart payload into dated NAVs, sorted by date. Rows carrying no usable value
         * are skipped (the API sends `"value":null` for a day it has no NAV for), and nothing
         * guarantees the API's ordering, so the result is sorted here. Null when the body is
         * unreadable or holds no NAV at all.
         */
        internal fun parseNavs(body: String): List<PricePoint>? {
            val resp = try {
                json.decodeFromString(NavResponse.serializer(), body)
            } catch (_: Exception) {
                return null
            }
            val points = resp.navs.mapNotNull { n ->
                val value = n.value ?: return@mapNotNull null
                if (value <= 0) return@mapNotNull null
                val date = try {
                    LocalDate.parse(n.date)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                PricePoint(date, value)
            }.sortedBy { it.date }
            return points.ifEmpty { null }
        }

        /** Upserts [live] over [embedded] by date: the live feed wins, the bundled rows fill the past. */
        internal fun mergeNavs(embedded: List<PricePoint>, live: List<PricePoint>): List<PricePoint> {
            if (live.isEmpty()) return embedded
            if (embedded.isEmpty()) return live
            val byDate = LinkedHashMap<LocalDate, PricePoint>(embedded.size + live.size)
            for (p in embedded) byDate[p.date] = p
            for (p in live) byDate[p.date] = p
            return byDate.values.sortedBy { it.date }
        }
    }
}

// --- The API's JSON shapes. ---

@Serializable
private data class NavRequest(
    val locale: String = "fr",
    val sId: String,
    val isinCode: String,
    val maxPeriodCode: String = "inception",
    val debug: String? = null,
    val displayBenchmark: Boolean = false,
)

@Serializable
private data class NavResponse(
    val fundName: String? = null,
    val navs: List<Nav> = emptyList(),
) {
    @Serializable
    data class Nav(val date: String, val value: Double? = null)
}

/**
 * The bundled NAV baselines, one CSV per fund next to this class in the app's Java resources
 * (`fin/android/market/<TICKER>-NAV.csv`, `# ` comment lines then `date,close`). They are copied
 * from the Go reference's `refdata/` snapshots; each file names its source and copy date.
 *
 * They are the OFFLINE answer, never the preferred one: [Airfund] overlays the live feed on top,
 * which both refreshes recent rows and extends the series. They matter because they always cover
 * the fund's launch, so a fresh install charts it from inception without a single successful fetch.
 * Parsed once, on first use.
 */
object EmbeddedNavs {
    private val cache = HashMap<String, List<PricePoint>>()

    /** The bundled NAVs of [ticker], date-sorted; empty when the fund carries no snapshot. */
    @Synchronized
    fun of(ticker: String): List<PricePoint> = cache.getOrPut(ticker) { load(ticker) }

    private fun load(ticker: String): List<PricePoint> {
        val stream = EmbeddedNavs::class.java.getResourceAsStream("$ticker-NAV.csv") ?: return emptyList()
        val points = mutableListOf<PricePoint>()
        stream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("date,")) continue
                val comma = line.indexOf(',')
                if (comma <= 0) continue
                val date = try {
                    LocalDate.parse(line.substring(0, comma))
                } catch (_: Exception) {
                    continue
                }
                val close = line.substring(comma + 1).toDoubleOrNull() ?: continue
                if (close <= 0) continue
                points.add(PricePoint(date, close))
            }
        }
        return points.sortedBy { it.date }
    }
}
