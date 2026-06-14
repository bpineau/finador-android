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

    private fun yahoo() = Yahoo(baseUrl = server.url("/").toString().trimEnd('/'))

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
}
