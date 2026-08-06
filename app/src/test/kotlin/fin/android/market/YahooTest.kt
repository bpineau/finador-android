package fin.android.market

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class YahooTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun yahoo() = Yahoo(
        baseUrl = server.url("/").toString().trimEnd('/'),
        cookieUrl = server.url("/cookie").toString(),
    )

    // 2024-01-15 00:00 UTC = 1705276800; 2024-01-16 = 1705363200; 2024-03-10 = 1710028800
    private val chartBody = """
        {"chart":{"result":[{
          "meta":{"currency":"USD"},
          "timestamp":[1705276800,1705363200],
          "events":{"dividends":{"1710028800":{"amount":1.25,"date":1710028800}}},
          "indicators":{"quote":[{"close":[450.0, null]}]}
        }],"error":null}}
    """.trimIndent()

    @Test fun dailyParsesClosesCurrencyDividends() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chartBody))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals("USD", data.currency)
        // null close on the 16th is skipped
        assertEquals(1, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.closes[0].date)
        assertEquals(450.0, data.closes[0].close, 0.0)
        assertEquals(1, data.dividends.size)
        assertEquals(LocalDate.parse("2024-03-10"), data.dividends[0].exDate)
        assertEquals(1.25, data.dividends[0].amount, 0.0)

        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v8/finance/chart/SPY?"))
        assertTrue(req.path!!.contains("interval=1d"))
        assertTrue(req.path!!.contains("events=div"))
        assertEquals(Http.USER_AGENT, req.getHeader("User-Agent"))
    }

    @Test fun dailyWithoutSymbolIsNull() {
        assertNull(yahoo().daily(Ref(symbol = null, isin = "LU0171310443"), LocalDate.parse("2024-01-01")))
        assertEquals(0, server.requestCount) // no request made
    }

    @Test fun fxToUsdParsesCcyUsdPair() {
        val fxBody = """
            {"chart":{"result":[{
              "meta":{"currency":"USD"},
              "timestamp":[1705276800],
              "indicators":{"quote":[{"close":[1.085]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(fxBody))
        val series = yahoo().fxToUsd("EUR", LocalDate.parse("2024-01-01"))!!
        assertEquals(1, series.points.size)
        assertEquals(1.085, series.points[0].close, 0.0)

        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v8/finance/chart/EURUSD%3DX?") || req.path!!.startsWith("/v8/finance/chart/EURUSD=X?"))
    }

    @Test fun retriesOnceOn500() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chartBody))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals(1, data.closes.size)
        assertEquals(2, server.requestCount) // initial 503 + retry
    }

    // The v7 quote API is the only source of an intraday price: the chart's daily bar is what a
    // provider publishes, the quote is what the market is doing. It needs a cookie + crumb pair.
    @Test fun quotesFetchesLivePricesInOneBatchedCall() {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"quoteResponse":{"result":[
                 {"symbol":"DDOG","currency":"USD","regularMarketPrice":229.29,"regularMarketTime":1785009601},
                 {"symbol":"EURUSD=X","currency":"USD","regularMarketPrice":1.1525,"regularMarketTime":1785011698},
                 {"symbol":"HALTED","currency":"USD","regularMarketPrice":0.0,"regularMarketTime":1785009601}]}}""",
            ),
        )

        val got = yahoo().quotes(listOf("DDOG", "EURUSD=X", "HALTED"))

        assertEquals(setOf("DDOG", "EURUSD=X"), got.keys) // a zero price is not a price
        assertEquals(229.29, got["DDOG"]!!.price, 0.0)
        assertEquals("USD", got["DDOG"]!!.currency)
        assertEquals(1785009601L, got["DDOG"]!!.time)

        server.takeRequest() // cookie
        server.takeRequest() // crumb
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v7/finance/quote?"))
        assertTrue(req.path!!.contains("crumb=crumb1"))
        assertEquals("A3=ck", req.getHeader("Cookie"))
    }

    // An expired crumb answers 401. Renewing the pair once and retrying is what keeps a long-lived
    // install quoting live prices instead of silently sliding back to end-of-day closes.
    @Test fun quotesRenewsStaleCrumbOnce() {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=old; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=new; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("crumb2"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"quoteResponse":{"result":[{"symbol":"DDOG","currency":"USD","regularMarketPrice":229.29,"regularMarketTime":1785009601}]}}""",
            ),
        )

        val got = yahoo().quotes(listOf("DDOG"))

        assertEquals(229.29, got["DDOG"]!!.price, 0.0)
        assertEquals(6, server.requestCount)
    }

    @Test fun quotesWithoutSymbolsMakesNoRequest() {
        assertEquals(emptyMap<String, Quote>(), yahoo().quotes(emptyList()))
        assertEquals(0, server.requestCount)
    }

    // A 429 is not an expired crumb. Renewing on it would throw a cookie + crumb + retry at a host
    // that is already throttling, which is how a refresh earns a ban - the very lag this fixes.
    @Test fun quotesDoesNotRenewAuthOnThrottling() {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(MockResponse().setResponseCode(429)) // first try
        server.enqueue(MockResponse().setResponseCode(429)) // the one built-in retry

        assertTrue(yahoo().quotes(listOf("DDOG")).isEmpty())
        assertEquals(4, server.requestCount) // cookie, crumb, quote, retry - and nothing more
    }

    // A quote with no timestamp is not a quote: dated at the epoch it would splice a 1970 point
    // into the cached series, where nothing ever removes it.
    @Test fun quotesSkipsPricesWithoutTimestamp() {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"quoteResponse":{"result":[
                 {"symbol":"HALTED","currency":"USD","regularMarketPrice":12.5}]}}""",
            ),
        )

        assertTrue(yahoo().quotes(listOf("HALTED")).isEmpty())
    }
}
