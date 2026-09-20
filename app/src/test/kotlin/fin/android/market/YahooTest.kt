package fin.android.market

import fin.android.net.FakeHttpServer
import fin.android.net.Http
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class YahooTest {
    private lateinit var server: FakeHttpServer

    @Before fun setUp() { server = FakeHttpServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun yahoo() = Yahoo(
        baseUrl = server.url("/").trimEnd('/'),
        cookieUrl = server.url("/cookie"),
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
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(chartBody))
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
        assertTrue(req.path.startsWith("/v8/finance/chart/SPY?"))
        assertTrue(req.path.contains("interval=1d"))
        assertTrue(req.path.contains("events=div"))
        assertEquals(Http.USER_AGENT, req.getHeader("User-Agent"))
    }

    @Test fun dailyReadsTheOpenToCloseFactors() {
        // The open column next to the close: a day carrying both gives a factor, a day missing the
        // open (a halted line, a source that serves none) gives nothing.
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"USD"},
              "timestamp":[1705276800,1705363200],
              "indicators":{"quote":[{"close":[450.0, 500.0],"open":[445.5, null]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals(1, data.openFactors.size)
        assertEquals(LocalDate.parse("2024-01-15"), data.openFactors[0].date)
        assertEquals(0.99, data.openFactors[0].close, 1e-12) // 445.5 / 450
    }

    @Test fun aPayloadWithoutAnOpenColumnCarriesNoFactor() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(chartBody))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertTrue(data.openFactors.isEmpty()) // the nowcast then stays anchored on the close
    }

    /**
     * A zero (or negative) close is a missing point, exactly like the null one: a provider serves
     * it on a halted or freshly listed line, and it is not a price. Stored, it would print a 0 in
     * a total, and on an FX series it would divide every conversion crossing that currency.
     */
    @Test fun dailySkipsANonPositiveClose() {
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"USD"},
              "timestamp":[1705276800,1705363200],
              "indicators":{"quote":[{"close":[0.0, 450.0]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals(1, data.closes.size)
        assertEquals(LocalDate.parse("2024-01-16"), data.closes[0].date)
        assertEquals(450.0, data.closes[0].close, 0.0)
    }

    @Test fun fxToUsdSkipsANonPositiveClose() {
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"USD"},
              "timestamp":[1705276800,1705363200],
              "indicators":{"quote":[{"close":[-1.0, 1.085]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val series = yahoo().fxToUsd("EUR", LocalDate.parse("2024-01-01"))!!
        assertEquals(listOf(1.085), series.points.map { it.close })
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
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(fxBody))
        val series = yahoo().fxToUsd("EUR", LocalDate.parse("2024-01-01"))!!
        assertEquals(1, series.points.size)
        assertEquals(1.085, series.points[0].close, 0.0)

        val req = server.takeRequest()
        assertTrue(req.path.startsWith("/v8/finance/chart/EURUSD%3DX?") || req.path.startsWith("/v8/finance/chart/EURUSD=X?"))
    }

    /** An FX series holds the USD value of one unit: a cross served in anything else is refused. */
    @Test fun fxToUsdRejectsANonUsdCross() {
        val fxBody = """
            {"chart":{"result":[{
              "meta":{"currency":"GBP"},
              "timestamp":[1705276800],
              "indicators":{"quote":[{"close":[0.855]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(fxBody))
        assertNull(yahoo().fxToUsd("EUR", LocalDate.parse("2024-01-01")))
    }

    @Test fun retriesOnceOn500() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(503))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(chartBody))
        val data = yahoo().daily(Ref(symbol = "SPY", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals(1, data.closes.size)
        assertEquals(2, server.requestCount) // initial 503 + retry
    }

    // The v7 quote API is the only source of an intraday price: the chart's daily bar is what a
    // provider publishes, the quote is what the market is doing. It needs a cookie + crumb pair.
    @Test fun quotesFetchesLivePricesInOneBatchedCall() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(
            FakeHttpServer.FakeResponse().setResponseCode(200).setBody(
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
        assertTrue(req.path.startsWith("/v7/finance/quote?"))
        assertTrue(req.path.contains("crumb=crumb1"))
        assertEquals("A3=ck", req.getHeader("Cookie"))
    }

    // An expired crumb answers 401. Renewing the pair once and retrying is what keeps a long-lived
    // install quoting live prices instead of silently sliding back to end-of-day closes.
    @Test fun quotesRenewsStaleCrumbOnce() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=old; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(401))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=new; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb2"))
        server.enqueue(
            FakeHttpServer.FakeResponse().setResponseCode(200).setBody(
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
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(429)) // first try
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(429)) // the one built-in retry

        assertTrue(yahoo().quotes(listOf("DDOG")).isEmpty())
        assertEquals(4, server.requestCount) // cookie, crumb, quote, retry - and nothing more
    }

    // A quote with no timestamp is not a quote: dated at the epoch it would splice a 1970 point
    // into the cached series, where nothing ever removes it.
    @Test fun quotesSkipsPricesWithoutTimestamp() {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(
            FakeHttpServer.FakeResponse().setResponseCode(200).setBody(
                """{"quoteResponse":{"result":[
                 {"symbol":"HALTED","currency":"USD","regularMarketPrice":12.5}]}}""",
            ),
        )

        assertTrue(yahoo().quotes(listOf("HALTED")).isEmpty())
    }

    // --- Venue sub-units: a London line comes back in PENCE (see Units). ---

    /**
     * Yahoo reports a London listing as `"currency":"GBp"` and prices it in pence. Handing that
     * number over as pounds is a 100x valuation error no plausibility check can see (rescaling a
     * series leaves every return untouched), and REFUSING it - which this client used to do, the
     * code being compared byte for byte against the holding's declared "GBP" - leaves the holding
     * at its cost basis for ever. The sub-unit is removed here instead, where the numbers enter.
     */
    @Test fun aLondonLineIsServedInPoundsNotPence() {
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"GBp","exchangeTimezoneName":"Europe/London"},
              "timestamp":[1705276800],
              "events":{"dividends":{"1705276800":{"amount":25.0,"date":1705276800}}},
              "indicators":{"quote":[{"close":[12345.0],"open":[12283.275]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "VOD.L", isin = null), LocalDate.parse("2024-01-01"))!!
        assertEquals("GBP", data.currency)
        assertEquals(123.45, data.closes[0].close, 1e-9)
        assertEquals(0.25, data.dividends[0].amount, 1e-9)
        // The open-to-close ratio is not a price and must survive the rescaling untouched.
        assertEquals(0.995, data.openFactors[0].close, 1e-9)
    }

    @Test fun aLondonQuoteIsServedInPoundsNotPence() {
        serveQuotes(
            """{"symbol":"VOD.L","currency":"GBp","exchangeTimezoneName":"Europe/London",
                "regularMarketPrice":12345.0,"regularMarketTime":1767312000,
                "postMarketPrice":12400.0,"postMarketTime":1767315600}""",
        )
        val q = yahoo().quotes(listOf("VOD.L"), extendedHours = true)["VOD.L"]!!
        assertEquals("GBP", q.currency)
        assertEquals(123.45, q.price, 1e-9)
        assertEquals(124.0, q.offHours!!.price, 1e-9) // the off-hours print shares the unit
    }

    // --- Trading days: a bar carries an instant of the session, not a date (see VenueDay). ---

    /**
     * The ASX opens at 10:00 in Sydney, 23:00 UTC of the day BEFORE while Australia is on summer
     * time. Read in UTC, Monday's session lands on the Sunday and Friday's on the Thursday, which
     * breaks every date-matched join the app makes (the previous close a day change reads, the FX
     * rate of the day, the overlap day the restatement canary compares) while looking ordinary.
     */
    @Test fun anAustralianSessionIsDatedInSydneyNotUtc() {
        // 1767567600 = Mon 2026-01-05 10:00 Sydney = Sun 2026-01-04 23:00 UTC.
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"AUD","exchangeTimezoneName":"Australia/Sydney"},
              "timestamp":[1767567600,1767654000],
              "events":{"dividends":{"1767567600":{"amount":0.5,"date":1767567600}}},
              "indicators":{"quote":[{"close":[10.0,11.0],"open":[9.9,10.9]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "NST.AX", isin = null), LocalDate.parse("2026-01-01"))!!
        assertEquals(
            listOf(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-06")),
            data.closes.map { it.date },
        )
        assertEquals(listOf(LocalDate.parse("2026-01-05")), data.dividends.map { it.exDate })
        assertEquals(
            listOf(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-06")),
            data.openFactors.map { it.date },
        )
    }

    /**
     * The correction is one-directional. A UTC reading is never LATE, only early, so a venue-local
     * date EARLIER than the UTC one is not a correction but a zone disagreeing with the instant -
     * and moving the point backwards could collide with a day the series already holds.
     */
    @Test fun aVenueWestOfGreenwichKeepsItsUtcDay() {
        // 1767661200 = Mon 2026-01-05 20:00 New York = Tue 2026-01-06 01:00 UTC.
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"USD","exchangeTimezoneName":"America/New_York"},
              "timestamp":[1767661200],
              "indicators":{"quote":[{"close":[100.0]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "AA", isin = null), LocalDate.parse("2026-01-01"))!!
        assertEquals(LocalDate.parse("2026-01-06"), data.closes[0].date)
    }

    /**
     * A currency cross has no exchange and no trading day, and the zone Yahoo attaches to one is
     * decorative. It stays on the UTC calendar its whole history was built on: re-dating it would
     * move a point onto a day the series already holds, and the later value would overwrite the
     * earlier one - a lost session, worse than a misplaced one.
     */
    @Test fun aCurrencyCrossIsNeverRedatedByTimeZone() {
        // The same Sydney-evening instant, this time on a cross Yahoo tags with a venue zone.
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"USD","exchangeTimezoneName":"Australia/Sydney"},
              "timestamp":[1767567600],
              "indicators":{"quote":[{"close":[0.68]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val series = yahoo().fxToUsd("AUD", LocalDate.parse("2026-01-01"))!!
        assertEquals(LocalDate.parse("2026-01-04"), series.points[0].date) // the UTC day, untouched
    }

    /** An unknown or absent zone name leaves the UTC reading exactly as it was. */
    @Test fun anUnknownZoneLeavesTheUtcDay() {
        val body = """
            {"chart":{"result":[{
              "meta":{"currency":"AUD","exchangeTimezoneName":"Mars/Olympus"},
              "timestamp":[1767567600],
              "indicators":{"quote":[{"close":[10.0]}]}
            }],"error":null}}
        """.trimIndent()
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(body))
        val data = yahoo().daily(Ref(symbol = "NST.AX", isin = null), LocalDate.parse("2026-01-01"))!!
        assertEquals(LocalDate.parse("2026-01-04"), data.closes[0].date)
    }

    // --- Extended hours (parity with the Go reference's `value --extended`). ---

    /**
     * Fixtures copied from the Go reference's session_test.go: the live Yahoo v7 answers captured on
     * 2026-09-09 at 21:10 New York, stripped to the fields this client reads.
     */
    private fun quoteResponse(vararg results: String) =
        """{"quoteResponse":{"result":[${results.joinToString(",")}],"error":null}}"""

    // DDOG, after hours: the 19:59:25 print is 43 cents above the 16:00 close.
    private val ddogPost = """{"symbol":"DDOG","currency":"USD","marketState":"POSTPOST",
        "regularMarketPrice":225.27,"regularMarketTime":1788984001,
        "postMarketPrice":225.7,"postMarketTime":1788998365}"""

    // DDOG the next morning at 08:00 New York: a pre-market print, with last night's post-market
    // one still served alongside it.
    private val ddogPre = """{"symbol":"DDOG","currency":"USD","marketState":"PRE",
        "regularMarketPrice":225.27,"regularMarketTime":1788984001,
        "postMarketPrice":225.7,"postMarketTime":1788998365,
        "preMarketPrice":228.4,"preMarketTime":1789041600}"""

    // DDOG mid-session: the morning's pre-market print is still served, and is now the older one.
    private val ddogRegular = """{"symbol":"DDOG","currency":"USD","marketState":"REGULAR",
        "regularMarketPrice":229.1,"regularMarketTime":1789056000,
        "preMarketPrice":228.4,"preMarketTime":1789041600}"""

    // A weekend, every session shut and no off-hours field left.
    private val ddogClosed = """{"symbol":"DDOG","currency":"USD","marketState":"CLOSED",
        "regularMarketPrice":225.27,"regularMarketTime":1788984001}"""

    // IWDA.AS: an Amsterdam line runs no extended session, so Yahoo serves no pre/post field at all
    // (hasPrePostMarketData false).
    private val iwdaNone = """{"symbol":"IWDA.AS","currency":"EUR","marketState":"PREPRE",
        "regularMarketPrice":126.05,"regularMarketTime":1788968108}"""

    private fun serveQuotes(vararg results: String) {
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=ck; Path=/"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody("crumb1"))
        server.enqueue(FakeHttpServer.FakeResponse().setResponseCode(200).setBody(quoteResponse(*results)))
    }

    // With the opt-in, the after-hours print is reported apart and says which session it came from;
    // the regular print stays in place, which is what keeps it out of every stored series.
    @Test fun quotesReportTheAfterHoursPrintUnderTheOptIn() {
        serveQuotes(ddogPost)
        val q = yahoo().quotes(listOf("DDOG"), extendedHours = true)["DDOG"]!!
        assertEquals(225.27, q.price, 0.0)
        assertEquals(1788984001L, q.time)
        assertEquals(Session.Print(225.7, 1788998365L, "post"), q.offHours)
    }

    // The very same answer, without the opt-in: the regular close and nothing else.
    @Test fun theDefaultPathNeverLooksAtOffHoursFields() {
        serveQuotes(ddogPost)
        val q = yahoo().quotes(listOf("DDOG"))["DDOG"]!!
        assertEquals(225.27, q.price, 0.0)
        assertNull(q.offHours)
    }

    // The sessions a US line goes through, same fixtures and same expectations as the Go reference.
    @Test fun quotesWalkTheSessionsOfAUsLine() {
        for ((symbol, fixture, want) in listOf(
            // The pre-market print beats last night's post-market one.
            Triple("DDOG", ddogPre, Session.Print(228.4, 1789041600L, "pre")),
            Triple("DDOG", ddogRegular, null), // the regular session ignores the stale pre print
            Triple("DDOG", ddogClosed, null), // closed falls back to the regular close
            Triple("IWDA.AS", iwdaNone, null), // a venue without extended hours serves no such field
        )) {
            server.shutdown() // one server per case: each needs its own auth dance
            server = FakeHttpServer().also { it.start() }
            serveQuotes(fixture)
            assertEquals(want, yahoo().quotes(listOf(symbol), extendedHours = true)[symbol]!!.offHours)
        }
    }
}
