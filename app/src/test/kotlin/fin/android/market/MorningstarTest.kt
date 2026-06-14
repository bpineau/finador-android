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

class MorningstarTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun ms(): Morningstar {
        val url = server.url("/").toString().trimEnd('/')
        return Morningstar(base = url, boursoBase = url)
    }

    // Boursorama returns an HTML fragment carrying the Morningstar 0P… id in an OPCVM/tracker link.
    private val boursoHtml = """
        <ul class="search__list">
          <li><a href="/bourse/opcvm/cours/0P00000ABC/">My Fund</a></li>
        </ul>
    """.trimIndent()

    // 2024-01-15 = 1705276800000 ms; 2024-01-16 = 1705363200000 ms; 2024-01-17 = 1705449600000 ms
    private val compactJson = "[[1705276800000,101.5],[1705363200000,-1.0],[1705449600000,103.0]]"

    @Test fun dailyResolvesViaBoursoramaThenFetchesNav() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(boursoHtml))
        server.enqueue(MockResponse().setResponseCode(200).setBody(compactJson))

        val data = ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01"))!!
        assertNull(data.currency) // Morningstar doesn't disclose currency
        // value <= 0 on the 16th is skipped; 15th and 17th kept
        assertEquals(2, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.closes[0].date)
        assertEquals(101.5, data.closes[0].close, 0.0)
        assertEquals(LocalDate.parse("2024-01-17"), data.closes[1].date)
        assertEquals(103.0, data.closes[1].close, 0.0)

        val boursoReq = server.takeRequest()
        assertTrue(boursoReq.path!!.startsWith("/recherche/ajax?"))
        assertTrue(boursoReq.path!!.contains("query=FR0000000000"))
        assertEquals("XMLHttpRequest", boursoReq.getHeader("X-Requested-With"))

        val navReq = server.takeRequest()
        assertTrue(navReq.path!!.startsWith("/api/rest.svc/timeseries_price/ok91jeenoo?"))
        assertTrue(navReq.path!!.contains("id=0P00000ABC"))
        assertTrue(navReq.path!!.contains("outputType=COMPACTJSON"))
    }

    @Test fun noIsinIsNull() {
        assertNull(ms().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01")))
        assertEquals(0, server.requestCount)
    }

    @Test fun boursoramaWithoutIdIsNull() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<ul></ul>")) // no 0P… link
        assertNull(ms().daily(Ref(symbol = null, isin = "FR0000000000"), LocalDate.parse("2024-01-01")))
        assertEquals(1, server.requestCount) // resolution failed → no NAV call
    }
}
