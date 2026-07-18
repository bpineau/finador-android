package fin.android.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `ts` string is sealed into every record and is the merge's last-writer-wins key, so its
 * shape (Go RFC3339Nano) and its chronological ordering are contract, not cosmetics.
 */
class TimestampsTest {

    @Test fun wholeSecondFormatsWithoutFraction() {
        assertEquals("2026-01-02T03:04:05Z", Rfc3339.format(Instant.parse("2026-01-02T03:04:05Z")))
    }

    @Test fun fractionTrimsTrailingZeros() {
        // 123ms = 123000000ns: Go's RFC3339Nano renders ".123", never ".123000000".
        assertEquals(
            "2026-01-02T03:04:05.123Z",
            Rfc3339.format(Instant.parse("2026-01-02T03:04:05.123000000Z")),
        )
    }

    @Test fun singleNanoKeepsFullPrecision() {
        assertEquals(
            "2026-01-02T03:04:05.000000005Z",
            Rfc3339.format(Instant.ofEpochSecond(1767323045, 5)),
        )
    }

    @Test fun instantRoundTripsFormat() {
        val t = Instant.parse("2026-01-02T03:04:05.123456789Z")
        assertEquals(t, Rfc3339.instant(Rfc3339.format(t)))
    }

    @Test fun malformedTsCollapsesToEpoch() {
        assertEquals(Instant.EPOCH, Rfc3339.instant("not-a-timestamp"))
        assertEquals(Instant.EPOCH, Rfc3339.instant(""))
    }

    /**
     * Why the merge must order by parsed instant: a whole second renders without a fraction
     * ("…03Z") and 'Z' > '.', so the LEXICAL order of the two strings below is reversed from
     * their chronological order - a string sort would elect the older write.
     */
    @Test fun instantOrderIsChronologicalWhereStringOrderIsNot() {
        val whole = "2026-01-01T00:00:03Z"
        val later = "2026-01-01T00:00:03.5Z"
        assertTrue("string order misleads", whole > later)
        assertTrue("instant order is chronological", Rfc3339.instant(whole) < Rfc3339.instant(later))
    }
}
