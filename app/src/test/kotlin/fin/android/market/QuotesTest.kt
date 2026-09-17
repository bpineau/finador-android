package fin.android.market

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import fin.android.domain.TaxRule
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Tests the refresh orchestration of [Quotes]: which instruments are fetched, how new closes and
 * dividends merge into the existing cache, and which FX series are pulled (every currency the book
 * or the display can need, USD excepted). Prices come from a fake provider; FX goes through a
 * [Yahoo] pointed at a MockWebServer.
 */
class QuotesTest {
    private lateinit var server: MockWebServer

    /** Serves a 1-point close series for any `/v8/finance/chart/<CCY>USD=X` FX request. */
    private val fxBody = """
        {"chart":{"result":[{
          "meta":{"currency":"USD"},
          "timestamp":[1705276800],
          "indicators":{"quote":[{"close":[1.085]}]}
        }],"error":null}}
    """.trimIndent()

    /** The v7 quote payload this server answers with; empty by default (no live quote). */
    private var quoteBody = """{"quoteResponse":{"result":[]}}"""

    @Before fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        // The cookie bootstrap: the header is the point, not the body.
                        path.startsWith("/cookie") ->
                            MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/")
                        path.startsWith("/v1/test/getcrumb") -> MockResponse().setResponseCode(200).setBody("crumb1")
                        path.startsWith("/v7/finance/quote") -> MockResponse().setResponseCode(200).setBody(quoteBody)
                        else -> MockResponse().setResponseCode(200).setBody(fxBody)
                    }
                }
            }
            it.start()
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun yahoo() = Yahoo(
        baseUrl = server.url("/").toString().trimEnd('/'),
        cookieUrl = server.url("/cookie").toString(),
    )

    /** Provider stub answering every ref with the same closes/dividends. */
    private class FakeProvider(private val data: DailyData) : Provider {
        override val name = "fake"
        val seen = mutableListOf<Ref>()
        val from = mutableListOf<LocalDate>()
        override fun daily(ref: Ref, from: LocalDate): DailyData? {
            seen += ref
            this.from += from
            return data
        }
    }

    private fun d(s: String) = LocalDate.parse(s)

    private fun book() = Book(
        accounts = mapOf("cto" to Account("cto", "CTO", "EUR", TaxRule.None)),
        assets = mapOf(
            "aa" to Asset("aa", AssetKind.SECURITY, "Alpha", ticker = "AA", ccy = "USD"),
            "prop" to Asset("prop", AssetKind.PROPERTY, "Flat", ccy = "EUR"),
            "noid" to Asset("noid", AssetKind.SECURITY, "NoId", ccy = "EUR"), // no ticker/isin
        ),
    )

    @Test fun refreshMergesPricesAndFetchesEveryNeededFx() {
        val provider = FakeProvider(
            DailyData(
                currency = "USD",
                closes = listOf(PricePoint(d("2026-06-02"), 110.0)),
                dividends = listOf(DividendEvent(d("2026-05-01"), 2.0)),
            ),
        )
        val existing = MarketData(
            prices = mapOf("aa" to PriceSeries(listOf(PricePoint(d("2026-06-01"), 100.0)))),
            dividends = mapOf("aa" to listOf(DividendEvent(d("2026-01-01"), 1.0))),
        )
        val now = d("2026-06-03")
        val out = Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = now,
            referenceCcy = "CHF", multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        // Only the identified security was quoted (property and ticker/isin-less skipped).
        assertEquals(listOf(Ref("AA", null)), provider.seen)
        assertNull(out.prices["prop"])
        assertNull(out.prices["noid"])

        // New close merged after the cached one; the refresh day is recorded.
        assertEquals(listOf(100.0, 110.0), out.prices["aa"]!!.points.map { it.close })
        assertEquals(now, out.prices["aa"]!!.fetchedAt)

        // Dividends upsert by ex-date: the cached January event survives the incremental fetch.
        assertEquals(listOf(d("2026-01-01"), d("2026-05-01")), out.dividends["aa"]!!.map { it.exDate })

        // FX pulled for every needed non-USD currency: EUR (asset/account) + CHF (display).
        assertEquals(setOf("EUR", "CHF"), out.fx.keys)
        assertEquals(now, out.fx["EUR"]!!.fetchedAt)
        assertFalse("USD needs no series (it is the pivot)", out.fx.containsKey("USD"))
    }

    /**
     * The declared currency is the contract on the DAILY pass too, not only on the spot one: a
     * provider answering in another currency (Yahoo's GBp pence metadata, an FT twin listing) would
     * otherwise have its whole close series merged into a series the valuation reads as the declared
     * currency - a position wrong by the cross, and persisted. Mirrors the guard of the Go
     * reference's `internal/market/refresh.go`.
     */
    @Test fun offCurrencyDailySeriesIsIgnored() {
        val provider = FakeProvider(
            DailyData(
                currency = "GBp", // "AA" is declared in USD
                closes = listOf(PricePoint(d("2026-06-02"), 12345.0)),
                dividends = listOf(DividendEvent(d("2026-05-01"), 2.0)),
            ),
        )
        val out = Quotes.refresh(
            book(), null, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        assertNull("off-currency closes must not reach the cache", out.prices["aa"])
        assertNull("nor its dividends", out.dividends["aa"])
        // The rejected currency must not drag an FX series along either.
        assertFalse(out.fx.containsKey("GBp"))
    }

    /** A cached series survives an off-currency answer untouched: the refresh simply adds nothing. */
    @Test fun offCurrencyDailySeriesLeavesTheCachedOneIntact() {
        val provider = FakeProvider(
            DailyData(currency = "GBp", closes = listOf(PricePoint(d("2026-06-02"), 12345.0))),
        )
        val existing = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(listOf(PricePoint(d("2026-06-01"), 100.0)), fetchedAt = d("2026-06-01")),
            ),
        )
        val out = Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        assertEquals(listOf(100.0), out.prices["aa"]!!.points.map { it.close })
        // fetchedAt stays unstamped, so a later run tries again (as the Go reference does).
        assertEquals(d("2026-06-01"), out.prices["aa"]!!.fetchedAt)
    }

    @Test fun refreshedDividendOverwritesSameExDate() {
        val provider = FakeProvider(
            DailyData(
                currency = null,
                closes = listOf(PricePoint(d("2026-06-02"), 110.0)),
                dividends = listOf(DividendEvent(d("2026-01-01"), 1.5)), // corrected amount
            ),
        )
        val existing = MarketData(dividends = mapOf("aa" to listOf(DividendEvent(d("2026-01-01"), 1.0))))
        val out = Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        assertEquals(1, out.dividends["aa"]!!.size)
        assertEquals(1.5, out.dividends["aa"]!![0].amount, 0.0)
    }

    @Test fun failedProviderKeepsExistingSeries() {
        val provider = object : Provider {
            override val name = "down"
            override fun daily(ref: Ref, from: LocalDate): DailyData? = null
        }
        val existing = MarketData(
            prices = mapOf("aa" to PriceSeries(listOf(PricePoint(d("2026-06-01"), 100.0)), fetchedAt = d("2026-06-01"))),
        )
        val out = Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        // No provider data: the cached series survives untouched (fetchedAt included).
        assertEquals(existing.prices["aa"], out.prices["aa"])
        assertTrue(out.fx.keys.isNotEmpty()) // FX still refreshed independently
    }

    // The whole point of the spot pass: a live quote replaces today's daily bar, which a provider
    // may still be publishing at yesterday's level hours into a session.
    @Test fun liveQuoteOverwritesTodaysClose() {
        val provider = FakeProvider(
            DailyData(currency = "USD", closes = listOf(PricePoint(d("2026-06-03"), 100.0))),
        )
        quoteBody = """{"quoteResponse":{"result":[
          {"symbol":"AA","currency":"USD","regularMarketPrice":80.0,"regularMarketTime":1780507800}]}}"""

        val out = Quotes.refresh(
            book(), MarketData(), from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        // 1780507800 = 2026-06-03 17:30 UTC: same day as the daily bar, so it replaces it.
        assertEquals(listOf(80.0), out.prices["aa"]!!.points.map { it.close })
    }

    // A quote from a twin listing in another currency must never splice into the series.
    @Test fun offCurrencyQuoteIsDropped() {
        val provider = FakeProvider(
            DailyData(currency = "USD", closes = listOf(PricePoint(d("2026-06-03"), 100.0))),
        )
        quoteBody = """{"quoteResponse":{"result":[
          {"symbol":"AA","currency":"EUR","regularMarketPrice":80.0,"regularMarketTime":1780507800}]}}"""

        val out = Quotes.refresh(
            book(), MarketData(), from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        assertEquals(listOf(100.0), out.prices["aa"]!!.points.map { it.close })
    }

    // Re-downloading years of closes per asset on every refresh is what gets a phone throttled -
    // and a throttled fetch falls through to end-of-day providers, which is the lag itself.
    @Test fun deepSeriesRefetchesOnlyFromItsLastClose() {
        val provider = FakeProvider(DailyData(currency = "USD", closes = emptyList()))
        val existing = MarketData(
            prices = mapOf(
                "aa" to PriceSeries(
                    listOf(PricePoint(d("2025-12-01"), 90.0), PricePoint(d("2026-06-02"), 100.0)),
                ),
            ),
        )

        Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        assertEquals(listOf(d("2026-06-02")), provider.from)
    }

    // A series that does not reach the floor yet must still be back-filled in full.
    @Test fun shallowSeriesRefetchesFromTheFloor() {
        val provider = FakeProvider(DailyData(currency = "USD", closes = emptyList()))
        val existing = MarketData(
            prices = mapOf("aa" to PriceSeries(listOf(PricePoint(d("2026-06-02"), 100.0)))),
        )

        Quotes.refresh(
            book(), existing, from = d("2026-01-01"), now = d("2026-06-03"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        assertEquals(listOf(d("2026-01-01")), provider.from)
    }

    // --- Employee-savings funds: the NAV source, its proxy, and the estimated tail. ---

    /** A book holding one FCPE (and nothing of its proxy, which must be fetched all the same). */
    private fun fcpeBook() = Book(
        accounts = mapOf("pee" to Account("pee", "PEE", "EUR", TaxRule.None)),
        assets = mapOf(
            "fcpe" to Asset("fcpe", AssetKind.SECURITY, "Monde M", ticker = "ERESMONDEM", ccy = "EUR"),
        ),
    )

    /** Provider stub answering per symbol: the fund's NAVs, the proxy's closes. */
    private class BySymbolProvider(private val data: Map<String, DailyData>) : Provider {
        override val name = "by-symbol"
        val seen = mutableListOf<String>()
        override fun daily(ref: Ref, from: LocalDate): DailyData? {
            ref.symbol?.let { seen += it }
            return data[ref.symbol]
        }
    }

    private fun fcpeProvider() = BySymbolProvider(
        mapOf(
            // Two published NAVs, the last one on the 18th.
            "ERESMONDEM" to DailyData(
                currency = "EUR",
                closes = listOf(PricePoint(d("2026-08-17"), 50.0), PricePoint(d("2026-08-18"), 51.0)),
            ),
            // The proxy in USD, closed two days further.
            "URTH" to DailyData(
                currency = "USD",
                closes = listOf(
                    PricePoint(d("2026-08-17"), 100.0), PricePoint(d("2026-08-18"), 102.0),
                    PricePoint(d("2026-08-19"), 104.0), PricePoint(d("2026-08-20"), 106.0),
                ),
            ),
        ),
    )

    @Test fun aHeldFundGetsItsProxyFetchedAndItsTailEstimated() {
        val provider = fcpeProvider()
        val out = Quotes.refresh(
            fcpeBook(), MarketData(), from = d("2026-01-01"), now = d("2026-08-20"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        // The proxy is fetched though the user holds none of it, and cached apart from the assets.
        assertEquals(listOf("ERESMONDEM", "URTH"), provider.seen)
        assertEquals(4, out.prices[Nowcast.proxyKey("URTH")]!!.points.size)

        // The fund's own series: two published NAVs then two estimated days, flagged from the first.
        val series = out.prices["fcpe"]!!
        assertEquals(listOf(50.0, 51.0, 52.0, 53.0), series.points.map { Math.round(it.close * 1e6) / 1e6 })
        assertEquals(d("2026-08-19"), series.estimatedFrom)
        assertEquals("URTH", series.estimateProxy)
    }

    @Test fun aFundStruckAtTheOpenIsEstimatedFromTheProxysOpen() {
        // The single-stock FCPE carries NavAnchor.OPEN, so its tail divides by the proxy's OPEN of
        // the last NAV's day (101), not by that day's close (102) - the Go reference's own literals.
        val book = Book(
            accounts = mapOf("pee" to Account("pee", "PEE", "EUR", TaxRule.None)),
            assets = mapOf(
                "ddog" to Asset("ddog", AssetKind.SECURITY, "Datadog C", ticker = "ERES_DATADOG", ccy = "EUR"),
            ),
        )
        val days = listOf(d("2026-08-17"), d("2026-08-18"), d("2026-08-19"), d("2026-08-20"))
        val closes = listOf(100.0, 102.0, 104.0, 106.0)
        val opens = listOf(99.0, 101.0, 103.0, 105.0)
        val provider = BySymbolProvider(
            mapOf(
                "ERES_DATADOG" to DailyData(
                    currency = "EUR",
                    closes = listOf(PricePoint(days[0], 50.0), PricePoint(days[1], 51.0)),
                ),
                "DDOG" to DailyData(
                    currency = "USD",
                    closes = days.mapIndexed { i, day -> PricePoint(day, closes[i]) },
                    openFactors = days.mapIndexed { i, day -> PricePoint(day, opens[i] / closes[i]) },
                ),
            ),
        )

        val out = Quotes.refresh(
            book, MarketData(), from = d("2026-01-01"), now = d("2026-08-20"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )

        val series = out.prices["ddog"]!!
        assertEquals(51 * 104 / 101.0, series.points[2].close, 1e-9)
        assertEquals(51 * 106 / 101.0, series.points[3].close, 1e-9)
        assertEquals(d("2026-08-19"), series.estimatedFrom)
        // The factors anchor the estimate and are stored nowhere: the proxy's cached series holds
        // its closes alone.
        assertEquals(closes, out.prices[Nowcast.proxyKey("DDOG")]!!.points.map { it.close })
    }

    @Test fun theEstimatedTailIsRecomputedNotCompounded() {
        val first = Quotes.refresh(
            fcpeBook(), MarketData(), from = d("2026-01-01"), now = d("2026-08-20"),
            multi = MultiSource(listOf(fcpeProvider())), yahoo = yahoo(),
        )
        // Refreshing over the previous result must strip its estimates before merging: the tail is
        // read off the proxy of the moment, never off the estimate of the previous run.
        val second = Quotes.refresh(
            fcpeBook(), first, from = d("2026-01-01"), now = d("2026-08-20"),
            multi = MultiSource(listOf(fcpeProvider())), yahoo = yahoo(),
        )
        assertEquals(first.prices["fcpe"], second.prices["fcpe"])
        assertEquals(4, second.prices["fcpe"]!!.points.size)
    }

    @Test fun anUnreachableProxyLeavesTheFundAtItsLastNav() {
        val provider = BySymbolProvider(
            mapOf(
                "ERESMONDEM" to DailyData(
                    currency = "EUR",
                    closes = listOf(PricePoint(d("2026-08-17"), 50.0), PricePoint(d("2026-08-18"), 51.0)),
                ),
            ),
        )
        val out = Quotes.refresh(
            fcpeBook(), MarketData(), from = d("2026-01-01"), now = d("2026-08-20"),
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        val series = out.prices["fcpe"]!!
        assertEquals(listOf(50.0, 51.0), series.points.map { it.close })
        assertNull(series.estimatedFrom)
        assertNull(out.prices[Nowcast.proxyKey("URTH")])
    }

    @Test fun theProxysLiveQuoteEstimatesTheRunningSession() {
        // 1787270400 = 2026-08-21 00:00 UTC. The proxy trades at 159, half again its 106 close.
        quoteBody = """{"quoteResponse":{"result":[
          {"symbol":"URTH","currency":"USD","regularMarketPrice":159.0,"regularMarketTime":1787270400},
          {"symbol":"EURUSD=X","currency":"USD","regularMarketPrice":1.085,"regularMarketTime":1787270400}]}}"""
        val out = Quotes.refresh(
            fcpeBook(), MarketData(), from = d("2026-01-01"), now = d("2026-08-21"),
            multi = MultiSource(listOf(fcpeProvider())), yahoo = yahoo(),
        )
        val series = out.prices["fcpe"]!!
        assertEquals(d("2026-08-21"), series.points.last().date)
        assertEquals(79.5, series.points.last().close, 1e-9) // 53 * 159 / 106
        assertEquals(d("2026-08-19"), series.estimatedFrom) // the whole tail is still an estimate
        // The proxy's own live price is recorded too, so the next refresh anchors on it.
        assertEquals(159.0, out.prices[Nowcast.proxyKey("URTH")]!!.points.last().close, 0.0)
    }

    // --- Extended hours: shown, never stored (parity with Go's SpotRefreshExtended). ---

    /** DDOG-shaped answer for AA: a 19:59 after-hours print above the 16:00 close (see YahooTest). */
    private val postPrintBody = """{"quoteResponse":{"result":[
      {"symbol":"AA","currency":"USD","regularMarketPrice":225.27,"regularMarketTime":1788984001,
       "postMarketPrice":225.7,"postMarketTime":1788998365}]}}"""

    private fun refreshBoth(provider: Provider, now: LocalDate = d("2026-09-09")): Pair<MarketData, Quotes.Refresh> {
        val plain = Quotes.refresh(
            book(), MarketData(), from = d("2026-01-01"), now = now,
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        val extended = Quotes.refreshExtended(
            book(), MarketData(), from = d("2026-01-01"), now = now,
            multi = MultiSource(listOf(provider)), yahoo = yahoo(),
        )
        return plain to extended
    }

    @Test fun theOptInReportsTheOffHoursPrint() {
        quoteBody = postPrintBody
        val out = Quotes.refreshExtended(
            book(), MarketData(), from = d("2026-01-01"), now = d("2026-09-09"),
            multi = MultiSource(listOf(FakeProvider(DailyData(currency = "USD", closes = emptyList())))),
            yahoo = yahoo(),
        )

        val print = out.offHours["aa"]!!
        assertEquals("AA", print.ticker)
        assertEquals("USD", print.ccy)
        assertEquals(225.7, print.price, 0.0)
        assertEquals(1788998365L, print.time)
        assertEquals(Session.POST, print.session)
        // The regular print's instant travels with it: that is what the print's validity, re-read
        // every time the screen is emitted, is measured against (Session.stillCurrent).
        assertEquals(1788984001L, print.regularTime)
        // And the series holds the regular print, never the off-hours one.
        assertEquals(listOf(225.27), out.market.prices["aa"]!!.points.map { it.close })
    }

    // The invariant the whole feature hangs on: the opt-in changes what is SHOWN, never what is
    // STORED - not the in-memory series, and not the encrypted sidecar the app writes from it.
    @Test fun theOptInLeavesTheStoredDataAndItsSidecarIdentical() {
        quoteBody = postPrintBody
        val (plain, extended) = refreshBoth(
            FakeProvider(DailyData(currency = "USD", closes = listOf(PricePoint(d("2026-09-09"), 220.0)))),
        )

        assertEquals(plain, extended.market)
        // 1788984001 = 2026-09-09 20:00 UTC: the REGULAR print replaced the day's bar in both.
        assertEquals(225.27, extended.market.prices["aa"]!!.points.last().close, 0.0)

        // Through the real door: same key, same content read back (the nonce is random, the
        // plaintext is not).
        val key = ByteArray(32) { it.toByte() }
        val dir = java.nio.file.Files.createTempDirectory("sidecar").toFile()
        val a = java.io.File(dir, "plain.cache").also { CacheSidecar.write(it, key, plain) }
        val b = java.io.File(dir, "extended.cache").also { CacheSidecar.write(it, key, extended.market) }
        assertEquals(CacheSidecar.read(a, key), CacheSidecar.read(b, key))
        dir.deleteRecursively()
    }

    // An employee-savings fund is priced by an estimate read off a proxy's REGULAR print. It is not
    // a spot target, so it can never be labelled with a session - and the proxy's own off-hours
    // print must not move the estimate either.
    @Test fun anEstimatedFundNeverTakesASession() {
        quoteBody = """{"quoteResponse":{"result":[
          {"symbol":"URTH","currency":"USD","regularMarketPrice":159.0,"regularMarketTime":1787270400,
           "postMarketPrice":200.0,"postMarketTime":1787280000},
          {"symbol":"EURUSD=X","currency":"USD","regularMarketPrice":1.085,"regularMarketTime":1787270400}]}}"""
        val out = Quotes.refreshExtended(
            fcpeBook(), MarketData(), from = d("2026-01-01"), now = d("2026-08-21"),
            multi = MultiSource(listOf(fcpeProvider())), yahoo = yahoo(),
        )

        assertTrue(out.offHours.isEmpty())
        val series = out.market.prices["fcpe"]!!
        assertEquals(79.5, series.points.last().close, 1e-9) // 53 * 159 / 106, the regular print
        assertEquals(d("2026-08-19"), series.estimatedFrom) // still flagged as the estimate it is
    }
}
