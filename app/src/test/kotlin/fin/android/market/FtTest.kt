package fin.android.market

import fin.android.net.FakeHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class FtTest {
    private lateinit var server: FakeHttpServer

    @Before fun setUp() { server = FakeHttpServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun ft() = Ft(baseUrl = server.url("/").trimEnd('/'))

    private val searchBody = """
        {"data":{"security":[
          {"name":"Some Fund","symbol":"LU0171310443:EUR","xid":"123456","isPrimary":true}
        ]}}
    """.trimIndent()

    private val seriesBody = """
        {
          "Dates":["2024-01-15T00:00:00","2024-01-16T00:00:00","2024-01-17T00:00:00"],
          "Elements":[{
            "Currency":"EUR",
            "ComponentSeries":[
              {"Type":"Open","Values":[1.0,2.0,3.0]},
              {"Type":"Close","Values":[101.5, null, 103.0]}
            ]
          }]
        }
    """.trimIndent()

    @Test fun dailyResolvesViaSearchThenSeries() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(searchBody))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(seriesBody))

        val data = ft().daily(Ref(symbol = null, isin = "LU0171310443"), LocalDate.parse("2024-01-01"))!!
        assertEquals("EUR", data.currency)
        // null close on the 16th is skipped; 15th and 17th kept
        assertEquals(2, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.closes[0].date)
        assertEquals(101.5, data.closes[0].close, 0.0)
        assertEquals(LocalDate.parse("2024-01-17"), data.closes[1].date)
        assertEquals(103.0, data.closes[1].close, 0.0)

        val searchReq = server.takeRequest()
        assertTrue(searchReq.path.startsWith("/data/searchapi/searchsecurities?"))
        assertTrue(searchReq.path.contains("query=LU0171310443"))

        val seriesReq = server.takeRequest()
        assertEquals("POST", seriesReq.method)
        assertEquals("/data/chartapi/series", seriesReq.path)
        val sent = seriesReq.body
        assertTrue(sent.contains("\"Symbol\":\"123456\"")) // posts the resolved xid
        assertTrue(sent.contains("\"dataPeriod\":\"Day\""))
    }

    @Test fun skipsGbxAndPicksNonPence() {
        val multi = """
            {"data":{"security":[
              {"name":"Pence listing","symbol":"AAA:LSE:GBX","xid":"111","isPrimary":true},
              {"name":"Euro listing","symbol":"BBB:EUR","xid":"222","isPrimary":false}
            ]}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(multi))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(seriesBody))

        ft().daily(Ref(symbol = null, isin = "X"), LocalDate.parse("2024-01-01"))!!
        server.takeRequest() // search
        val seriesReq = server.takeRequest()
        assertTrue(seriesReq.body.contains("\"Symbol\":\"222\"")) // picked the EUR xid, not the GBX one
    }

    /**
     * When only the pence listing exists, FT serves it under "GBX" - the same sub-unit Yahoo spells
     * "GBp". The numbers are rescaled into pounds here, at the provider boundary, so a GBP holding
     * is priced by it instead of having the whole series refused on a currency-code comparison.
     */
    @Test fun aGbxListingIsServedInPounds() {
        val penceOnly = """
            {"data":{"security":[
              {"name":"Pence listing","symbol":"AAA:LSE:GBX","xid":"111","isPrimary":true}
            ]}}
        """.trimIndent()
        val pencePrices = """
            {
              "Dates":["2024-01-15T00:00:00"],
              "Elements":[{"Currency":"GBX","ComponentSeries":[{"Type":"Close","Values":[12345.0]}]}]
            }
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(penceOnly))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(pencePrices))

        val data = ft().daily(Ref(symbol = null, isin = "GB00B16GWD56"), LocalDate.parse("2024-01-01"))!!
        assertEquals("GBP", data.currency)
        assertEquals(123.45, data.closes[0].close, 1e-9)
    }

    @Test fun noIdentifierIsNull() {
        assertNull(ft().daily(Ref(symbol = null, isin = null), LocalDate.parse("2024-01-01")))
        assertEquals(0, server.requestCount)
    }
}
