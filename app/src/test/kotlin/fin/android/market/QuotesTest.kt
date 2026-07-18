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

    @Before fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(200).setBody(fxBody)
            }
            it.start()
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun yahoo() = Yahoo(baseUrl = server.url("/").toString().trimEnd('/'))

    /** Provider stub answering every ref with the same closes/dividends. */
    private class FakeProvider(private val data: DailyData) : Provider {
        override val name = "fake"
        val seen = mutableListOf<Ref>()
        override fun daily(ref: Ref, from: LocalDate): DailyData? {
            seen += ref
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
}
