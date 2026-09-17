package fin.android.ui

import fin.android.market.FxRate
import fin.android.market.Quotes
import fin.android.market.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * The gains-table cell formatter: grouped thousands, exactly one decimal, a leading minus for
 * negatives, NO leading "+", NO currency code. Grouping separator is a space (Locale.US symbols
 * overridden in [Format]).
 */
class FormatTest {
    @Test fun groupedOneDecimalNoPlus() {
        assertEquals("1 234.6", formatGainCell(1234.56))
    }

    @Test fun negativeUsesLeadingMinus() {
        assertEquals("-12.3", formatGainCell(-12.34))
    }

    @Test fun smallPositiveRoundsToOneDecimal() {
        assertEquals("0.0", formatGainCell(0.04))
    }

    @Test fun noPlusOnPositives() {
        val s = formatGainCell(42.0)
        assertEquals("42.0", s)
        assertEquals(false, s.startsWith("+"))
    }

    @Test fun orDashHandlesNull() {
        assertEquals("-", formatGainCellOrDash(null))
        assertEquals("12.3", formatGainCellOrDash(12.34))
    }

    @Test fun gainPercentOneDecimalNoPlus() {
        assertEquals("12.3%", formatGainPercent(0.1234))
        assertEquals("-4.6%", formatGainPercent(-0.0456))
        assertEquals("-", formatGainPercent(null))
    }

    @Test fun fxRateHasFourDecimalsAndItsDate() {
        val r = FxRate("USD", "EUR", 0.85431234, LocalDate.parse("2024-01-12"))
        assertEquals("1 USD = 0.8543 EUR (2024-01-12)", formatFxRate(r))
    }

    @Test fun fxRateWithoutADateStatesOnlyTheRate() {
        assertEquals("1 USD = 1.0000 EUR", formatFxRate(FxRate("USD", "EUR", 1.0, null)))
    }

    /**
     * An off-hours print reads in the DEVICE's zone: the same instant is 19:59 for a holder in New
     * York and 01:59 the next day for one in Paris. 1788998365 is the Go reference's own
     * after-hours fixture (2026-09-09 19:59:25 New York).
     */
    @Test fun offHoursPrintReadsInTheDeviceZone() {
        val print = Quotes.OffHoursPrint("DDOG", "USD", 225.7, 1788998365L, Session.POST, regularTime = 1788984001L)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("post 19:59", formatOffHours(print))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            assertEquals("post 01:59", formatOffHours(print))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** A pre-market print names its own session. 1789041600 = 2026-09-10 08:00 New York. */
    @Test fun aPreMarketPrintNamesThePreSession() {
        val print = Quotes.OffHoursPrint("DDOG", "USD", 228.4, 1789041600L, Session.PRE, regularTime = 1788984001L)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("pre 08:00", formatOffHours(print))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun fxRateGroupsThousands() {
        // A weak currency against a strong one still reads: 4 decimals, spaces for thousands.
        assertEquals("1 JPY = 0.0061 EUR", formatFxRate(FxRate("JPY", "EUR", 0.006123, null)))
        assertEquals("1 EUR = 163.3210 JPY", formatFxRate(FxRate("EUR", "JPY", 163.321, null)))
    }
}
