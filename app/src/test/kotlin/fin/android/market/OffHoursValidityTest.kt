package fin.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * How long an off-hours print keeps pricing a screen ([Session.stillCurrent], [Quotes.current]).
 *
 * [Session.freshest] chooses a print when a refresh observes it; this is the other half, and the
 * one a phone needs: a screen is re-emitted on every sync, edit and setting change, so without a
 * validity a pre-market print taken at 08:14 would still be pricing the total at dinner time.
 *
 * Every instant below is New York's, the venue clock of the only listings Yahoo serves pre/post
 * fields for. 2026-09-09 is a Wednesday, 2026-09-11 a Friday.
 */
class OffHoursValidityTest {
    // 2026-09-09 16:00 New York: the regular session's last print, and the reference every
    // off-hours candidate had to beat to exist at all.
    private val regular = 1788984000L

    private val postPrint = Session.Print(225.7, 1788998365L, Session.POST) // Wed 19:59:25
    private val prePrint = Session.Print(228.4, 1789042440L, Session.PRE) // Thu 08:14

    private fun at(epoch: Long) = Instant.ofEpochSecond(epoch)

    @Test fun anAfterHoursPrintLivesUntilTheNextPreMarketOpen() {
        // Still the last trade there was, all night - the case the Go reference calls a feature.
        assertTrue(Session.stillCurrent(postPrint, regular, at(1789027140L))) // Thu 03:59
        assertFalse(Session.stillCurrent(postPrint, regular, at(1789027260L))) // Thu 04:01, pre opens
    }

    @Test fun aPreMarketPrintDiesAtTheRegularOpen() {
        assertTrue(Session.stillCurrent(prePrint, regular, at(1789046940L))) // Thu 09:29
        assertFalse(Session.stillCurrent(prePrint, regular, at(1789047060L))) // Thu 09:31, bell
        // And it is certainly not still pricing the total that evening.
        assertFalse(Session.stillCurrent(prePrint, regular, at(1789070400L))) // Thu 16:00
    }

    @Test fun aFridayEveningPrintCrossesTheWeekend() {
        val friday = Session.Print(225.7, 1789164000L, Session.POST) // Fri 18:00
        assertTrue(Session.stillCurrent(friday, regular, at(1789203600L))) // Sat 05:00
        assertTrue(Session.stillCurrent(friday, regular, at(1789372740L))) // Mon 03:59
        assertFalse(Session.stillCurrent(friday, regular, at(1789372860L))) // Mon 04:01
    }

    @Test fun aPrintTheRegularSessionHasCaughtUpWithIsGone() {
        // A later refresh can move the regular print alone (the venue reopened, the off-hours
        // fields went stale): the print then loses the one comparison it was accepted on.
        assertFalse(Session.stillCurrent(postPrint, postPrint.time, at(1789027140L)))
        assertFalse(Session.stillCurrent(postPrint, postPrint.time + 1, at(1789027140L)))
    }

    @Test fun aRegularPrintIsNeverAnOffHoursOne() {
        val close = Session.Print(225.27, 1788984000L, Session.REGULAR)
        assertFalse(Session.stillCurrent(close, regular - 1, at(1788984060L)))
    }

    @Test fun theFilterKeepsOnlyThePrintsStillInForce() {
        // Two lines, two sessions, as one extended-hours pass would report them.
        val prints = mapOf(
            "dd" to Quotes.OffHoursPrint("DDOG", "USD", 225.7, postPrint.time, Session.POST, regular),
            "aa" to Quotes.OffHoursPrint("AA", "USD", 228.4, prePrint.time, Session.PRE, regular),
        )
        // Thu 09:00, inside the pre-market: last night's after-hours print is history, the morning's
        // is what prices the screen.
        assertEquals(setOf("aa"), Quotes.current(prints, at(1789045200L)).keys)
        // Thu 16:00, the bell long rung: nothing off-hours prices anything, and the total falls
        // back to the closes on its own.
        assertTrue(Quotes.current(prints, at(1789070400L)).isEmpty())
        // Wed 20:30, right after the print: the evening's number stands.
        assertEquals(
            setOf("dd"),
            Quotes.current(prints.filterKeys { it == "dd" }, at(1788999000L)).keys,
        )
    }
}
