package fin.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 9-case table of the Go reference's `TestFreshestSession`
 * (`pofo/pkg/marketdata/session_test.go`), same regular print (100 at 1000) and same candidate
 * numbers: the two implementations must agree case for case, since they decide the same thing about
 * the same Yahoo answer.
 */
class SessionTest {

    private fun case(
        name: String,
        prePrice: Double? = null,
        preTime: Long? = null,
        postPrice: Double? = null,
        postTime: Long? = null,
        wantPrice: Double,
        wantTime: Long,
        wantSession: String,
    ) {
        val got = Session.freshest(100.0, 1000L, prePrice, preTime, postPrice, postTime)
        assertEquals(name, Session.Print(wantPrice, wantTime, wantSession), got)
    }

    @Test fun nothingButTheRegularPrint() =
        case("nothing but the regular print", wantPrice = 100.0, wantTime = 1000, wantSession = "regular")

    @Test fun aNewerPrePrintWins() =
        case(
            "a newer pre print wins", prePrice = 101.0, preTime = 1100,
            wantPrice = 101.0, wantTime = 1100, wantSession = "pre",
        )

    @Test fun anOlderPrePrintLoses() =
        case(
            "an older pre print loses", prePrice = 101.0, preTime = 900,
            wantPrice = 100.0, wantTime = 1000, wantSession = "regular",
        )

    @Test fun theFreshestOfTheTwoWins() =
        case(
            "the freshest of the two wins", prePrice = 101.0, preTime = 1100, postPrice = 102.0, postTime = 1200,
            wantPrice = 102.0, wantTime = 1200, wantSession = "post",
        )

    @Test fun anOlderPostPrintLosesToANewerPreOne() =
        case(
            "an older post print loses to a newer pre one",
            prePrice = 101.0, preTime = 1300, postPrice = 102.0, postTime = 1200,
            wantPrice = 101.0, wantTime = 1300, wantSession = "pre",
        )

    @Test fun aPriceWithoutATimestampIsNotAPrint() =
        case(
            "a price without a timestamp is not a print", prePrice = 101.0,
            wantPrice = 100.0, wantTime = 1000, wantSession = "regular",
        )

    @Test fun aTimestampWithoutAPriceIsNotAPrint() =
        case(
            "a timestamp without a price is not a print", preTime = 1100,
            wantPrice = 100.0, wantTime = 1000, wantSession = "regular",
        )

    @Test fun aZeroPriceIsNotAPrint() =
        case(
            "a zero price is not a print", prePrice = 0.0, preTime = 1100,
            wantPrice = 100.0, wantTime = 1000, wantSession = "regular",
        )

    @Test fun aTieKeepsTheRegularPrint() =
        case(
            "a tie keeps the regular print", prePrice = 101.0, preTime = 1000,
            wantPrice = 100.0, wantTime = 1000, wantSession = "regular",
        )

    // The one thing callers branch on: only an off-hours print may be displayed as one, and the
    // regular session is never called extended.
    @Test fun onlyPreAndPostAreExtended() {
        assertFalse(Session.Print(100.0, 1000, Session.REGULAR).extended)
        assertTrue(Session.Print(100.0, 1000, Session.PRE).extended)
        assertTrue(Session.Print(100.0, 1000, Session.POST).extended)
    }
}
