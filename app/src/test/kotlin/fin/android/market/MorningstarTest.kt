package fin.android.market

import fin.android.net.FakeHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MorningstarTest {
    private lateinit var server: FakeHttpServer

    @Before fun setUp() { server = FakeHttpServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun ms(): Morningstar = Morningstar(base = server.url("/").trimEnd('/'))

    // The screener's shape, captured from the live endpoint on 2026-09-20 (an invented fund here).
    private fun screener(vararg rows: String) =
        """{"total": ${rows.size},"page": 1,"pageSize": 10,"rows": [${rows.joinToString(",")}] }"""

    private fun row(secId: String, isin: String, currency: String = "CU${"$".repeat(5)}EUR") =
        """{"SecId": "$secId","Name": "Zephyr Global Equity A","isin": "$isin","currencyId": "$currency"}"""

    // 2024-01-15 = 1705276800000 ms; 2024-01-16 = 1705363200000 ms; 2024-01-17 = 1705449600000 ms
    private val compactJson = "[[1705276800000,101.5],[1705363200000,-1.0],[1705449600000,103.0]]"

    @Test fun dailyResolvesThroughTheScreenerThenFetchesTheSeries() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(screener(row("0P00000ABC", "FR0000000000"))))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(compactJson))

        val data = ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01"))!!
        assertEquals("EUR", data.currency) // the screener discloses it; the timeseries API does not
        // value <= 0 on the 16th is skipped; 15th and 17th kept
        assertEquals(2, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.closes[0].date)
        assertEquals(101.5, data.closes[0].close, 0.0)
        assertEquals(LocalDate.parse("2024-01-17"), data.closes[1].date)
        assertEquals(103.0, data.closes[1].close, 0.0)

        val screenerReq = server.takeRequest()
        assertTrue(screenerReq.path.startsWith("/api/rest.svc/klr5zyak8x/security/screener?"))
        assertTrue(screenerReq.path.contains("term=FR0000000000"))
        // Both universes, escaped: a share class sits in exactly one of them.
        assertTrue(screenerReq.path.contains("universeIds=FOALL%24%24ALL%7CETALL%24%24ALL"))
        assertTrue(screenerReq.path.contains("securityDataPoints=SecId%7CName%7Cisin%7CcurrencyId"))

        val navReq = server.takeRequest()
        assertTrue(navReq.path.startsWith("/api/rest.svc/timeseries_price/ok91jeenoo?"))
        // The bracket suffix, without which an exchange-traded id answers an EMPTY array with 200.
        assertTrue(navReq.path.contains("id=0P00000ABC%5D2%5D1%5D"))
        assertTrue(navReq.path.contains("outputType=COMPACTJSON"))
        assertTrue(navReq.path.contains("startDate=2024-01-01"))
    }

    /** An exact ISIN match wins over a full-text hit, so the series is never a sibling class. */
    @Test fun anExactIsinMatchWinsOverAFullTextHit() {
        server.enqueue(
            FakeHttpServer.FakeResponse().setResponseCode(200).setBody(
                screener(
                    row("0P00000AAA", "FR0000000001", "CU${"$".repeat(5)}USD"),
                    row("0P00000BBB", "FR0000000000"),
                ),
            ),
        )
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(compactJson))

        val data = ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01"))!!
        assertEquals("EUR", data.currency)
        server.takeRequest()
        assertTrue(server.takeRequest().path.contains("id=0P00000BBB%5D2%5D1%5D"))
    }

    /** A London class quotes in pence; the sub-unit is removed where the numbers enter the app. */
    @Test fun aPenceQuotingClassIsRescaledIntoPounds() {
        server.enqueue(
            FakeHttpServer.FakeResponse().setResponseCode(200)
                .setBody(screener(row("0P00000ABC", "GB0000000000", "CU${"$".repeat(5)}GBp"))),
        )
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(compactJson))

        val data = ms().daily(Ref(symbol = null, isin = "GB0000000000"), LocalDate.parse("2024-01-01"))!!
        assertEquals("GBP", data.currency)
        assertEquals(1.015, data.closes[0].close, 1e-9)
    }

    @Test fun noIsinIsNull() {
        assertNull(ms().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01")))
        assertEquals(0, server.requestCount)
    }

    @Test fun anUnknownIsinIsNullAndCostsOneCall() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(screener()))
        assertNull(ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01")))
        assertEquals(1, server.requestCount) // resolution failed, so no timeseries call
    }

    /** The service answers an EMPTY array with HTTP 200 rather than an error; that is not a series. */
    @Test fun anEmptySeriesIsNull() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(screener(row("0P00000ABC", "FR0000000000"))))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("[]"))
        assertNull(ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01")))
        assertEquals(2, server.requestCount)
    }

    @Test fun currencyPaddingIsRemovedAndNonsenseReadsAsUnknown() {
        assertEquals("EUR", Morningstar.currencyOf("CU${"$".repeat(5)}EUR"))
        assertEquals("USD", Morningstar.currencyOf("usd"))
        assertEquals("GBp", Morningstar.currencyOf("CU${"$".repeat(5)}GBp")) // sub-unit keeps its case
        assertNull(Morningstar.currencyOf(""))
        assertNull(Morningstar.currencyOf("CU${"$".repeat(5)}"))
    }
}
