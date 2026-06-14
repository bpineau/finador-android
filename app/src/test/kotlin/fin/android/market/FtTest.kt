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

class FtTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun ft() = Ft(baseUrl = server.url("/").toString().trimEnd('/'))

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
        server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(seriesBody))

        val data = ft().daily(Ref(symbol = null, isin = "LU0171310443"), LocalDate.parse("2024-01-01"))!!
        assertEquals("EUR", data.currency)
        // null close on the 16th is skipped; 15th and 17th kept
        assertEquals(2, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.closes[0].date)
        assertEquals(101.5, data.closes[0].close, 0.0)
        assertEquals(LocalDate.parse("2024-01-17"), data.closes[1].date)
        assertEquals(103.0, data.closes[1].close, 0.0)

        val searchReq = server.takeRequest()
        assertTrue(searchReq.path!!.startsWith("/data/searchapi/searchsecurities?"))
        assertTrue(searchReq.path!!.contains("query=LU0171310443"))

        val seriesReq = server.takeRequest()
        assertEquals("POST", seriesReq.method)
        assertEquals("/data/chartapi/series", seriesReq.path)
        val sent = seriesReq.body.readUtf8()
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
        server.enqueue(MockResponse().setResponseCode(200).setBody(multi))
        server.enqueue(MockResponse().setResponseCode(200).setBody(seriesBody))

        ft().daily(Ref(symbol = null, isin = "X"), LocalDate.parse("2024-01-01"))!!
        server.takeRequest() // search
        val seriesReq = server.takeRequest()
        assertTrue(seriesReq.body.readUtf8().contains("\"Symbol\":\"222\"")) // picked the EUR xid, not the GBX one
    }

    @Test fun noIdentifierIsNull() {
        assertNull(ft().daily(Ref(symbol = null, isin = null), LocalDate.parse("2024-01-01")))
        assertEquals(0, server.requestCount)
    }
}
