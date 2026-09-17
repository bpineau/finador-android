package fin.android.market

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The trading sessions a [Quote] can name, the rule that picks between them, and how long the
 * chosen one stays the freshest thing known.
 *
 * A faithful port of the Go reference's `freshestSession`
 * (`pofo/pkg/marketdata/session.go`, behind finador's `value --extended`): the same 9-case table
 * gates it in [SessionTest].
 */
object Session {
    const val REGULAR = "regular"
    const val PRE = "pre"
    const val POST = "post"

    /**
     * The venue clock every extended-hours print is read against. Yahoo serves the pre/post fields
     * for US listings only (`hasPrePostMarketData`), so the payload implies this zone and no other:
     * a European line has no extended session and never produces such a print.
     */
    private val VENUE: ZoneId = ZoneId.of("America/New_York")

    /** The venue's pre-market open, and the open of its regular session. */
    private val PRE_OPEN: LocalTime = LocalTime.of(4, 0)
    private val REGULAR_OPEN: LocalTime = LocalTime.of(9, 30)

    /** One observed print: a [price] struck at [time] (epoch seconds) during [session]. */
    data class Print(val price: Double, val time: Long, val session: String) {
        /** True for an off-hours print - a pre-market or after-hours trade, thinner than a close. */
        val extended: Boolean get() = session == PRE || session == POST
    }

    /**
     * Picks the most recent print among the regular session's and the venue's extended-hours ones,
     * and names the session it came from.
     *
     * The rule is pure recency: an extended-hours candidate wins only when it has both a price and
     * a timestamp STRICTLY after the regular one. That single guard is what makes the choice safe,
     * and it is deliberately preferred to Yahoo's `marketState` enum, which is both wider than
     * documented (PRE, PREPRE, REGULAR, POST, POSTPOST, CLOSED) and free to change. It also
     * disposes of the stale-field trap on its own: during the regular session Yahoo still serves the
     * morning's `preMarketPrice`, and before the pre-market opens it still serves last night's
     * `postMarketPrice`, each dated at the instant it was struck, so each is accepted only while it
     * really is the freshest thing known. That last case is a feature: at 03:00 in New York the last
     * trade IS yesterday evening's after-hours print, and it comes back labelled "post" with its own
     * time, which is more than the 16:00 close says.
     *
     * Prices at or below zero are ignored, and a price with no timestamp is not a print: dated at
     * the Unix epoch it would poison every caller.
     */
    fun freshest(
        price: Double,
        time: Long,
        prePrice: Double?,
        preTime: Long?,
        postPrice: Double?,
        postTime: Long?,
    ): Print {
        var best = Print(price, time, REGULAR)
        for ((candPrice, candTime, name) in listOf(
            Triple(prePrice, preTime, PRE),
            Triple(postPrice, postTime, POST),
        )) {
            if (candPrice == null || candTime == null) continue
            if (candPrice <= 0 || candTime <= best.time) continue
            best = Print(candPrice, candTime, name)
        }
        return best
    }

    /**
     * Whether an off-hours [print], observed alongside a regular print struck at [regularTime],
     * still prices a screen at [now].
     *
     * [freshest] decides at OBSERVATION time; this decides at USE time, and a screen outlives its
     * refresh. Without it, a pre-market print taken at 08:14 keeps pricing the total all day, long
     * after the open it precedes made it history - the very number the caption says is a thin,
     * pre-open trade.
     *
     * Two conditions, both borrowed from the rule that picked the print in the first place:
     *   - it must still be FRESHER than the regular session's last print ([freshest]'s own guard,
     *     re-read here because a later refresh can move the regular one alone);
     *   - its session must still be the CURRENT one: a pre-market print dies at the regular open
     *     of its own day, an after-hours print at the next session's pre-market open (so at 03:00
     *     in New York last evening's print is still the last trade there was, which is the case
     *     pofo's `freshestSession` calls a feature).
     *
     * Holidays are not modelled: only weekends push an after-hours print's expiry to the next
     * trading day. A holiday therefore expires the print one morning early, and the screen falls
     * back to the regular close - the conservative direction, and the same number a venue that
     * never opens would show anyway.
     */
    fun stillCurrent(print: Print, regularTime: Long, now: Instant): Boolean {
        if (!print.extended || print.time <= regularTime) return false
        val struck = Instant.ofEpochSecond(print.time).atZone(VENUE)
        val expiry = when (print.session) {
            PRE -> struck.toLocalDate().atTime(REGULAR_OPEN)
            else -> nextTradingDay(struck).atTime(PRE_OPEN)
        }
        return now.isBefore(expiry.atZone(VENUE).toInstant())
    }

    /** The next weekday after [struck]'s venue date, weekends skipped (see [stillCurrent]). */
    private fun nextTradingDay(struck: ZonedDateTime): LocalDate {
        var day = struck.toLocalDate().plusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
            day = day.plusDays(1)
        }
        return day
    }
}
