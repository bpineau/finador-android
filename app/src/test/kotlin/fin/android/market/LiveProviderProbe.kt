package fin.android.market

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Hits the REAL market-data providers over the network and prints what came back.
 *
 * This is not part of `make test` and never will be: the rest of the suite is hermetic (faked
 * HTTP, faked filesystem) and must stay that way. This class exists for the one question a
 * hermetic suite cannot answer - "does the HTTP stack still work against the live internet?" -
 * which matters after a change to the network layer, to the TLS story (a new `targetSdk` can
 * turn on Encrypted Client Hello or Certificate Transparency), or when a provider is suspected
 * of having changed its payload.
 *
 * Run it deliberately:
 *
 * ```sh
 * make probe
 * ```
 *
 * It asserts nothing about the numbers, because live numbers change every day. It asserts that
 * each provider answered with a plausible series, and it PRINTS a fingerprint (point count,
 * first and last observation, currency) so two runs - before and after a change - can be
 * compared by eye or with `diff`.
 *
 * Only public instruments are named here, as market-data vectors: a widely held US share, a
 * European UCITS ETF, a Luxembourg fund by ISIN, and the two employee-savings share classes the
 * app already knows by name. No account, no holding, no amount.
 */
class LiveProviderProbe {

    @Test
    fun probeLiveProviders() {
        assumeTrue("set -Dprobe=1 (or run `make probe`) to hit the live providers", enabled())
        val from = LocalDate.now().minusDays(60)
        val failures = mutableListOf<String>()

        fun record(label: String, ok: Boolean, detail: String) {
            println("probe  ${if (ok) "OK  " else "FAIL"}  ${label.padEnd(28)} $detail")
            if (!ok) failures += label
        }

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

        // The quote call is the one that needs Yahoo's cookie + crumb pair, so it exercises
        // reading a multi-valued Set-Cookie response header and re-sending it on the next call.
        val quotes = yahoo.quotes(listOf("AAPL", "MSFT"))
        record(
            "yahoo.quotes (cookie+crumb)",
            quotes.size == 2 && quotes.values.all { it.price > 0 },
            quotes.values.joinToString { "${it.symbol} ${it.price} ${it.currency}" },
        )

        val ft = Ft().daily(Ref(symbol = null, isin = "LU0171310443"), from)
        record("ft.daily LU0171310443", plausible(ft), describe(ft))

        val ms = Morningstar().daily(Ref(symbol = null, isin = "LU0171310443"), from)
        record("morningstar.daily (POST)", plausible(ms), describe(ms))

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

        check(failures.isEmpty()) { "live providers that did not answer plausibly: $failures" }
    }

    private fun enabled(): Boolean = System.getProperty("probe").isNullOrBlank().not()

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
