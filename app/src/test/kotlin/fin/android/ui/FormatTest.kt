package fin.android.ui

import fin.android.market.FxRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

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

    @Test fun fxRateGroupsThousands() {
        // A weak currency against a strong one still reads: 4 decimals, spaces for thousands.
        assertEquals("1 JPY = 0.0061 EUR", formatFxRate(FxRate("JPY", "EUR", 0.006123, null)))
        assertEquals("1 EUR = 163.3210 JPY", formatFxRate(FxRate("EUR", "JPY", 163.321, null)))
    }
}
