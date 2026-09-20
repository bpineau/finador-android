package fin.android.market

import fin.android.domain.DividendEvent
import fin.android.domain.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The venue sub-unit table and the two operations built on it ([Units.same], [Units.normalize]).
 * Mirrors the Go reference's `pkg/marketdata/units_test.go`.
 */
class UnitsTest {

    private fun d(s: String) = LocalDate.parse(s)

    /**
     * The whole trap in one assertion: "GBp" and "GBP" differ by case alone, so the table must be
     * read case-SENSITIVELY. Folding it would divide every pound price by a hundred.
     */
    @Test fun theTableIsReadCaseSensitively() {
        assertTrue(Units.isMinor("GBp"))
        assertFalse("GBP is the currency, not the sub-unit", Units.isMinor("GBP"))
        assertEquals("GBP" to 0.01, Units.major("GBp"))
        assertEquals("GBP" to 1.0, Units.major("GBP"))
    }

    @Test fun everyObservedSpellingIsCovered() {
        for ((code, want) in listOf("GBp" to "GBP", "GBX" to "GBP", "GBx" to "GBP", "ZAc" to "ZAR", "ZAC" to "ZAR", "ILA" to "ILS", "USX" to "USD")) {
            assertEquals(code, want to 0.01, Units.major(code))
        }
        // An ordinary code answers for itself, so a caller can apply the result unconditionally.
        assertEquals("EUR" to 1.0, Units.major("EUR"))
        assertEquals(null to 1.0, Units.major(null))
    }

    @Test fun sameFoldsCaseAndSubUnitButNotTwoCurrencies() {
        assertTrue(Units.same("GBp", "GBP"))
        assertTrue(Units.same("GBX", "gbp"))
        assertTrue(Units.same("ZAc", "ZAR"))
        assertFalse(Units.same("GBP", "USD"))
        assertFalse(Units.same("EUR", "USD"))
        // A provider that discloses no currency (Morningstar) says nothing, which is not a mismatch.
        assertTrue(Units.same(null, "EUR"))
        assertTrue(Units.same("EUR", null))
    }

    @Test fun normalizeRescalesClosesAndDividendsAndRelabels() {
        val pence = DailyData(
            currency = "GBp",
            closes = listOf(PricePoint(d("2026-01-02"), 12345.0)),
            dividends = listOf(DividendEvent(d("2026-01-02"), 25.0)),
        )
        val out = Units.normalize(pence)
        assertEquals("GBP", out.currency)
        assertEquals(123.45, out.closes[0].close, 1e-12)
        assertEquals(0.25, out.dividends[0].amount, 1e-12)
        // Idempotent: the result carries a code the table does not hold.
        assertSame(out, Units.normalize(out))
    }

    /**
     * An open-to-close RATIO carries no currency: rescaling it would destroy the anchor of every
     * nowcast struck at the open, and nothing downstream would ever notice.
     */
    @Test fun anOpenToCloseRatioIsNeverRescaled() {
        val pence = DailyData(
            currency = "GBp",
            closes = listOf(PricePoint(d("2026-01-02"), 12345.0)),
            openFactors = listOf(PricePoint(d("2026-01-02"), 0.995)),
        )
        assertEquals(0.995, Units.normalize(pence).openFactors[0].close, 1e-12)
    }

    @Test fun normalizeRescalesAQuoteAndItsOffHoursPrint() {
        val q = Quote("VOD.L", 12345.0, 1767312000L, "GBp", Session.Print(12400.0, 1767312100L, Session.POST))
        val out = Units.normalize(q)
        assertEquals("GBP", out.currency)
        assertEquals(123.45, out.price, 1e-12)
        assertEquals(124.0, out.offHours!!.price, 1e-12)
        // A quote in an ordinary currency is handed back untouched.
        val usd = Quote("AA", 100.0, 1767312000L, "USD")
        assertSame(usd, Units.normalize(usd))
    }
}
