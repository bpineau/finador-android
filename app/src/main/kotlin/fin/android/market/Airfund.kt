package fin.android.market

import fin.android.domain.PricePoint
import fin.android.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * [navAnchor] names which print of that session the NAV is struck on.
 */
data class AirfundFund(
    val ticker: String,
    val shareCode: String,
    val proxy: String,
    val proxyCcy: String,
    val ccy: String,
    val name: String,
    val navAnchor: NavAnchor = NavAnchor.CLOSE,
)

/**
 * Which print of its nowcast proxy a fund's NAV of a day is struck on.
 *
 * [CLOSE] is the convention of a fund valued after its market has closed, and the default.
 * [OPEN] belongs to a fund whose valuation rules name the OPENING price of the valuation day: its
 * nowcast must leave that session's open-to-close move out of the anchor, else the move stays on
 * the estimate for as long as that NAV is the last one (see [Nowcast], and the Go reference's
 * `nowcast_anchor` catalog field).
 */
enum class NavAnchor { CLOSE, OPEN }

/**
 * The known FCPE share classes and their nowcast proxies. Both entries are measured in the Go
 * reference (`../pofo/docs/eres-fcpe-design.md`); the caveats worth knowing here:
 *
 * - `ERESMONDEM` strikes its NAV on the official NAVs of the two MSCI World ETFs it holds, which
 *   value New York at the New York close. Only a US-listed tracker shares that clock: URTH
 *   correlates 0.875 daily with the fund, where a Xetra or LSE line correlates 0.62 or less
 *   (they close before the US afternoon). Hence a NYSE Arca proxy rather than a European one.
 * - `ERES_DATADOG` is a single-stock fund whose NAV is struck on the NASDAQ OPENING price, not the
 *   close, which is why it carries [NavAnchor.OPEN]: the estimate stands on the proxy's opening
 *   print of the last NAV's day. A close-anchored one would carry that day's open-to-close move as
 *   an offset until the next NAV lands (typically a percent, more on an earnings day; measured on
 *   the fund's 264 NAV spans since 2022-04-11, 3.8 % rmse on the close against 2.8 % on the open).
 *   It also valued WEEKLY (Fridays) until 2026-07-13, and daily since.
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
            navAnchor = NavAnchor.OPEN,
        ),
    )

    // Indexed by BOTH the ticker and the share code, uppercased. The desktop's
    // "asset add" resolves the ticker through the pofo catalog and can store the
    // share code (990000124099) as the asset's ticker rather than the ERES_DATADOG
    // symbol, so a synced ledger names the fund either way; both must match.
    private val index: Map<String, AirfundFund> = buildMap {
        for (f in ALL) {
            put(f.ticker.uppercase(), f)
            put(f.shareCode.uppercase(), f)
        }
    }

    /**
     * The fund an asset [key] names (its ticker or its share code), or null when the key is not an
     * FCPE this app knows.
     */
    fun byTicker(key: String?): AirfundFund? = key?.let { index[it.trim().uppercase()] }
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
    private fun post(url: String, payload: String): String? =
        Http.send(
            url,
            method = "POST",
            headers = mapOf("User-Agent" to Http.USER_AGENT, "Accept" to "application/json"),
            body = payload,
            // This API answers a POST with 201, so the whole 2xx range is a success.
        ).bodyIf { it in 200..299 }

    companion object {
        /** The NAV-history endpoint of the delivery API (POST JSON in, JSON out). */
        private const val CHART_PATH = "/api/v1/navs-evolution-chart/data"

        /**
         * The id of the chart widget embedded on the management company's fund pages. It is the
         * SITE's, not the fund's: one id serves every fund of the site. The API answers 500 without
         * it, which is why it is not optional.
         */
        private const val WIDGET_ID = "41481ca4-919c-46c0-9ca1-41a880ff4e8e"

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
