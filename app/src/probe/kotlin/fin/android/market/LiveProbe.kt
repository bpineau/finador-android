package fin.android.market

import fin.android.net.Http
import fin.android.net.Tls
import java.net.URI
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Hits the REAL market-data providers over the network and reports what came back.
 *
 * This lives in its own source directory because it is compiled into BOTH test source sets, and it
 * has to be: the host JVM and an Android device do not share a TLS stack, so a green host probe
 * says nothing about the phone and vice versa (see `net/Tls.kt`). `make probe` runs it on the host
 * JVM, `make probe-device` runs it on an emulator or a phone; a release owes one green run of the
 * SECOND, because that is the stack users have.
 *
 * It is never part of `make test`: the rest of the suite is hermetic (faked HTTP, faked filesystem)
 * and must stay that way. This exists for the one question a hermetic suite cannot answer - "does
 * the stack still work against the live internet?" - which matters after a change to the network
 * layer, to the TLS story (a new `targetSdk` can turn on Encrypted Client Hello or Certificate
 * Transparency), or when a provider is suspected of having changed its payload.
 *
 * It asserts nothing about the numbers, because live numbers change every day. It asserts that each
 * provider answered with a plausible series, and it PRINTS a fingerprint (point count, first and
 * last observation, currency) so two runs - before and after a change - can be compared by eye or
 * with `diff`.
 *
 * It opens with two blocks that make a failure below attributable:
 *
 * - `tls`: the same URL fetched with the PLATFORM's default handshake and with the one this app
 *   sends ([Tls]). Two different statuses there mean a provider is fingerprinting the ClientHello,
 *   which is a thing about the stack and not about this repository's parsing.
 * - `net`: one reachability line per provider host. `HTTP 429` is a throttle (wait, change network,
 *   or read the `tls` block again), no answer at all is a host that is gone, and `HTTP 200` beside
 *   a failing provider below is a payload change - the only one of the three that this repository
 *   can fix.
 *
 * Only public instruments are named here, as market-data vectors: a widely held US share, a
 * European UCITS ETF, a Luxembourg fund by ISIN, and the two employee-savings share classes the app
 * already knows by name. No account, no holding, no amount.
 */
object LiveProbe {

    /** A fund with a long public history, used as the ISIN vector for FT and Morningstar. */
    private const val FUND_ISIN = "LU0171310443"

    /**
     * Runs every check, sending each line to [log], and returns the labels that did not answer
     * plausibly. An empty list is a green probe.
     */
    fun run(log: (String) -> Unit): List<String> {
        val from = LocalDate.now().minusDays(60)
        val failures = mutableListOf<String>()

        fun record(label: String, ok: Boolean, detail: String) {
            log("probe  ${if (ok) "OK  " else "FAIL"}  ${label.padEnd(28)} $detail")
            if (!ok) failures += label
        }

        handshake(log)

        reachable(log, "yahoo", "https://query1.finance.yahoo.com/v8/finance/chart/AAPL?range=5d&interval=1d")
        reachable(log, "ft", "https://markets.ft.com/data/funds/tearsheet/summary?s=$FUND_ISIN")
        reachable(log, "morningstar", "https://lt.morningstar.com/api/rest.svc/timeseries_price/ok91jeenoo")
        reachable(log, "airfund", "https://core.communicate.airfund.io/")

        val yahoo = Yahoo()
        for (symbol in listOf("AAPL", "CW8.PA")) {
            val d = yahoo.daily(Ref(symbol = symbol, isin = null), from)
            record("yahoo.daily $symbol", plausible(d), describe(d))
        }

        val fx = yahoo.fxToUsd("EUR", from)
        record(
            "yahoo.fxToUsd EUR",
            fx != null && fx.points.size >= 20,
            "${fx?.points?.size ?: 0} points, last ${fx?.points?.lastOrNull()?.date} " +
                "= ${fx?.points?.lastOrNull()?.close}",
        )

        // The quote call is the one that needs Yahoo's cookie + crumb pair, so it exercises reading
        // a multi-valued Set-Cookie response header and re-sending it on the next call.
        val quotes = yahoo.quotes(listOf("AAPL", "MSFT"))
        record(
            "yahoo.quotes (cookie+crumb)",
            quotes.size == 2 && quotes.values.all { it.price > 0 },
            quotes.values.joinToString { "${it.symbol} ${it.price} ${it.currency}" },
        )

        val ft = Ft().daily(Ref(symbol = null, isin = FUND_ISIN), from)
        record("ft.daily $FUND_ISIN", plausible(ft), describe(ft))

        val ms = Morningstar().daily(Ref(symbol = null, isin = FUND_ISIN), from)
        record("morningstar.daily $FUND_ISIN", plausible(ms), describe(ms))

        // Airfund answers a POST with 201 and returns the whole history; `from` is ignored.
        // The bundled snapshot would answer too, so compare against the LIVE rows only.
        val airfund = Airfund()
        for (fund in AirfundFunds.ALL) {
            val navs = airfund.fetchNavs(fund)
            record(
                "airfund.fetchNavs ${fund.ticker}",
                navs != null && navs.size >= 20,
                "${navs?.size ?: 0} navs, last ${navs?.lastOrNull()?.date} = ${navs?.lastOrNull()?.close}",
            )
        }

        return failures
    }

    /**
     * The A/B that tells a fingerprinted handshake from anything else: the same URL, same headers,
     * same second, fetched with the platform's default cipher-suite list and with the narrowed one
     * this app sends. Prints both statuses and asserts nothing - on a platform whose defaults are
     * already clean (Android) the two lines are identical, and that is the expected reading.
     */
    private fun handshake(log: (String) -> Unit) {
        val platform = SSLSocketFactory.getDefault() as SSLSocketFactory
        val url = "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?range=5d&interval=1d"
        log("probe  tls   ${"suites offered".padEnd(28)} ${suites(platform)}")
        log("probe  tls   ${"platform default".padEnd(28)} ${status(url, platform)}")
        log("probe  tls   ${"this app (net/Tls.kt)".padEnd(28)} ${status(url, Tls.socketFactory)}")
    }

    /**
     * How many cipher suites the platform enables and how many survive [Tls.filter]. Equal counts
     * mean the platform enables nothing obsolete and this app changes nothing at all, which is the
     * expected reading on Android.
     */
    private fun suites(platform: SSLSocketFactory): String = try {
        val enabled = (platform.createSocket() as SSLSocket)
            .use { it.enabledCipherSuites }
        "${enabled.size} by the platform, ${Tls.filter(enabled).size} after the filter"
    } catch (e: Exception) {
        "unreadable: ${e.javaClass.simpleName}"
    }

    /** One bare GET through [factory], reported as a status or as the transport failure. */
    private fun status(url: String, factory: SSLSocketFactory): String = try {
        val conn = URI(url).toURL().openConnection() as HttpsURLConnection
        conn.sslSocketFactory = factory
        conn.setRequestProperty("User-Agent", Http.USER_AGENT)
        conn.connectTimeout = Http.DEFAULT_CONNECT_TIMEOUT_MS
        conn.readTimeout = Http.DEFAULT_READ_TIMEOUT_MS
        val code = conn.responseCode
        val suite = conn.cipherSuite
        conn.disconnect()
        "HTTP $code, negotiated $suite"
    } catch (e: Exception) {
        "no answer: ${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * Prints one line saying whether [url]'s host answered, and with what. Asserts nothing: a
     * provider may legitimately answer 404 or 403 to a bare URL - the point is to separate "the host
     * is there" from "the host is throttling us" (429), "the host is gone" (no answer) and "the host
     * answered fine, so a null series below means the payload changed".
     */
    private fun reachable(log: (String) -> Unit, label: String, url: String) {
        val r = Http.send(url, headers = mapOf("User-Agent" to Http.USER_AGENT))
        val detail = if (r.answered) {
            "HTTP ${r.code}, ${r.body.length} bytes" + if (r.code == 429) "  <- throttled or fingerprinted" else ""
        } else {
            "no answer: ${r.failure?.javaClass?.simpleName}: ${r.failure?.message}"
        }
        log("probe  net   ${"reach $label".padEnd(28)} $detail")
    }

    private fun plausible(d: DailyData?): Boolean =
        d != null && d.closes.size >= 20 && d.closes.all { it.close > 0 } &&
            d.closes.zipWithNext().all { (a, b) -> a.date < b.date }

    private fun describe(d: DailyData?): String = when {
        d == null -> "no data"
        d.closes.isEmpty() -> "empty series"
        else -> "${d.closes.size} closes ${d.closes.first().date}..${d.closes.last().date}, " +
            "last ${d.closes.last().close}, ccy ${d.currency ?: "-"}, ${d.dividends.size} dividends"
    }
}
