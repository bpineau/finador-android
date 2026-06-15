package fin.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
