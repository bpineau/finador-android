package fin.android.market

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Covers the FCPE NAV source: the delivery API's payload (which answers 201, may send its rows in
 * any order and may carry a null value), the bundled offline baseline, and the merge that lets the
 * live feed win a shared date while the baseline keeps the fund's launch in view.
 */
class AirfundTest {
    private lateinit var server: MockWebServer
    private var status = 201
    private var body = ""

    private fun d(s: String) = LocalDate.parse(s)

    @Before fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(status).setBody(body)
            }
            it.start()
        }
    }

    @After fun tearDown() = server.shutdown()

    private fun airfund() = Airfund(baseUrl = server.url("/").toString().trimEnd('/'))

    private val fund = AirfundFunds.byTicker("ERESMONDEM")!!

    @Test fun parsesA201PayloadSortingRowsAndDroppingNulls() {
        status = 201
        body = """
            {"fundName":"ERES XTRACKERS ACTIONS MONDE","navs":[
              {"date":"2026-08-20","value":70.79},
              {"date":"2026-08-18","value":71.74},
              {"date":"2026-08-19","value":null},
              {"date":"not-a-date","value":12.0}
            ]}
        """.trimIndent()
        val navs = airfund().fetchNavs(fund)!!
        assertEquals(2, navs.size)
        assertEquals(d("2026-08-18"), navs[0].date) // unsorted input, sorted output
        assertEquals(71.74, navs[0].close, 1e-9)
        assertEquals(d("2026-08-20"), navs[1].date) // the null value and the bad date are dropped
    }

    @Test fun postsTheShareCodeAndTheWidgetId() {
        status = 201
        body = """{"fundName":"x","navs":[{"date":"2026-08-20","value":70.79}]}"""
        airfund().fetchNavs(fund)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/navs-evolution-chart/data", req.path)
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        val sent = req.body.readUtf8()
        assertTrue(sent, sent.contains("\"isinCode\":\"990000135629\""))
        assertTrue(sent, sent.contains("\"sId\":")) // required: the API answers 500 without it
        assertTrue(sent, sent.contains("\"maxPeriodCode\":\"inception\""))
    }

    @Test fun unreadableBodyYieldsNoNavs() {
        status = 201
        body = "<html>nope</html>"
        assertNull(airfund().fetchNavs(fund))
    }

    @Test fun embeddedBaselinesCoverEachFundsLaunch() {
        val monde = EmbeddedNavs.of("ERESMONDEM")
        assertEquals(d("2024-03-05"), monde.first().date)
        assertEquals(50.00, monde.first().close, 1e-9)

        val datadog = EmbeddedNavs.of("ERES_DATADOG")
        assertEquals(d("2021-07-22"), datadog.first().date)
        assertEquals(100.00, datadog.first().close, 1e-9)

        // Both are date-sorted and hold nothing else than positive NAVs.
        for (navs in listOf(monde, datadog)) {
            assertTrue(navs.size > 100)
            assertEquals(navs.sortedBy { it.date }, navs)
            assertTrue(navs.all { it.close > 0 })
        }
    }

    @Test fun aFailedCallFallsBackOnTheEmbeddedBaseline() {
        status = 500
        body = ""
        val data = airfund().daily(Ref("ERES_DATADOG", null), d("2026-01-01"))
        assertNotNull(data)
        assertEquals("EUR", data!!.currency)
        assertEquals(EmbeddedNavs.of("ERES_DATADOG"), data.closes)
    }

    @Test fun liveRowsWinOnASharedDateAndTheBaselineKeepsThePast() {
        status = 201
        // One row restating an embedded date with another value, one row past the baseline's end.
        body = """
            {"fundName":"ACTIONS DATADOG","navs":[
              {"date":"2021-07-22","value":123.0},
              {"date":"2026-09-01","value":210.0}
            ]}
        """.trimIndent()
        val closes = airfund().daily(Ref("ERES_DATADOG", null), d("2026-01-01"))!!.closes
        val embedded = EmbeddedNavs.of("ERES_DATADOG")
        assertEquals(embedded.size + 1, closes.size)
        assertEquals(d("2021-07-22"), closes.first().date) // inception is still in view
        assertEquals(123.0, closes.first().close, 1e-9) // the live row wins it
        assertEquals(d("2026-09-01"), closes.last().date) // and extends the series
        assertEquals(210.0, closes.last().close, 1e-9)
    }

    @Test fun theFetchIgnoresItsFromDate() {
        // The API knows no start date and returns everything; trimming here would cost a fresh
        // install the fund's launch, which is exactly what the baseline exists to keep.
        status = 500
        val full = airfund().daily(Ref("ERESMONDEM", null), d("1970-01-01"))!!.closes
        val late = airfund().daily(Ref("ERESMONDEM", null), d("2026-08-01"))!!.closes
        assertEquals(full, late)
    }

    @Test fun anUnknownTickerIsNotCovered() {
        status = 201
        body = """{"fundName":"x","navs":[{"date":"2026-08-20","value":70.79}]}"""
        assertNull(airfund().daily(Ref("SPY", null), d("2026-01-01")))
        assertNull(airfund().daily(Ref(null, "IE00B4L5Y983"), d("2026-01-01")))
        assertEquals(0, server.requestCount) // and costs no request at all
    }

    @Test fun theRegistryIsCaseInsensitiveAndComplete() {
        assertEquals(fund, AirfundFunds.byTicker("eresmondem"))
        assertNull(AirfundFunds.byTicker(null))
        assertEquals(2, AirfundFunds.ALL.size)
        assertTrue(AirfundFunds.ALL.all { it.ccy == "EUR" && it.proxyCcy == "USD" })
    }
}
