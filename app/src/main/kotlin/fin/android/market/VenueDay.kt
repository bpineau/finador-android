package fin.android.market

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar day a provider's bar or print belongs to.
 *
 * A daily bar carries an INSTANT of the trading session, not a date, and reading it in UTC is wrong
 * by a whole day for every venue whose session opens before midnight UTC. The ASX opens at 10:00 in
 * Sydney, which is 23:00 UTC of the day before while Australia is on summer time: half of every
 * Australian history then lands one day early, Monday's session on a SUNDAY and Friday's on a
 * Thursday. Mis-dated points break every date-matched join the app makes (the day change reads the
 * previous close, a conversion picks the day's rate, the restatement canary compares a shared date)
 * while looking perfectly ordinary.
 *
 * So the day is read in the venue's own zone, which Yahoo reports as `exchangeTimezoneName`.
 * Mirrors the Go reference's `sessionDay` (`pofo/pkg/marketdata/types.go`).
 *
 * `java.time` resolves zone names natively from API 26 (the app's minSdk), so no tzdata is bundled
 * for this; an unknown or absent name simply leaves the UTC reading in place.
 */
internal object VenueDay {

    /**
     * The zone to date [symbol]'s bars in, given the [reported] `exchangeTimezoneName`. Null means
     * "stay on the UTC calendar": an unusable name, or a currency cross.
     *
     * A CROSS IS EXEMPT from the venue-zone reading: it has no exchange and no trading day. Its
     * daily BARS are dated by [crossDateOf] instead (see [barDay]); its dividends (none) and its
     * live quote keep the UTC reading, as in the Go reference, whose `venueTimezone` returns no
     * zone for a cross either.
     */
    fun zoneOf(symbol: String, reported: String?): ZoneId? {
        if (isCross(symbol)) return null
        val name = reported?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            ZoneId.of(name)
        } catch (_: Exception) {
            null
        }
    }

    /** Yahoo spells every currency and crypto cross `<PAIR>=X`; nothing else carries that suffix. */
    fun isCross(symbol: String): Boolean = symbol.endsWith("=X")

    /**
     * How [symbol]'s daily bars are dated, given the [reported] `exchangeTimezoneName`: a currency
     * cross by [crossDateOf], anything else by [dateOf] in the venue's zone ([zoneOf]).
     */
    fun barDay(symbol: String, reported: String?): (Long) -> LocalDate {
        if (isCross(symbol)) return { crossDateOf(it, reported) }
        val zone = zoneOf(symbol, reported)
        return { dateOf(it, zone) }
    }

    /**
     * The first London date on which Yahoo's daily bar of a currency cross carries the rate at its
     * own opening instant, i.e. the PREVIOUS session's close, rather than its own day's rate.
     * Measured in pofo on EURUSD=X, GBPUSD=X, JPY=X and CHF=X against the FRED noon fixings: the
     * four crosses flip together, the same day.
     */
    private val CROSS_BAR_SWITCH: LocalDate = LocalDate.of(2010, 8, 4)

    /**
     * The calendar day a Yahoo daily bar of a currency cross belongs to, a session being the day
     * whose closing rate the bar holds. Mirrors pofo's `crossSessionDay` (`pkg/marketdata/types.go`,
     * v0.2.88), which the Go reference reads its crosses through (Go D55).
     *
     * Yahoo stamps every completed cross bar at MIDNIGHT LONDON ([reported], the payload's
     * `exchangeTimezoneName`, Europe/London when absent), and since [CROSS_BAR_SWITCH] the bar's
     * close is the rate at that instant: its level is that of the evening before. The bar stamped
     * Tuesday 00:00 is therefore Monday's close, and the one stamped Monday 00:00 is FRIDAY's.
     * Before the switch the bar held its own day's rate and keeps its London date. A bar stamped at
     * any other instant is the live quote of the day in progress, and keeps its day.
     *
     * The UTC dating this replaces read midnight London as 00:00 UTC in winter (every point a day
     * LATE, a Friday close converted at Thursday's rate) and as 23:00 UTC the day before in summer
     * (Monday's bar on a Sunday, Friday with no point at all). Either way a date-matched join, a
     * conversion at the rate of the record's own day above all, read the wrong session.
     */
    fun crossDateOf(epochSeconds: Long, reported: String?): LocalDate {
        val name = reported?.trim()?.takeIf { it.isNotEmpty() } ?: "Europe/London"
        val zone = try {
            ZoneId.of(name)
        } catch (_: Exception) {
            ZoneOffset.UTC
        }
        val t = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        var day = t.toLocalDate()
        val midnight = t.hour == 0 && t.minute == 0 && t.second == 0
        if (!midnight || day.isBefore(CROSS_BAR_SWITCH)) return day
        day = day.minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        return day
    }

    /**
     * The civil day of [epochSeconds] in [zone], FORWARD ONLY: the UTC reading stands whenever the
     * venue's own is earlier.
     *
     * The one-directional rule is deliberate. A UTC reading is never LATE, only early, since it
     * drops the hours a venue east of Greenwich has already lived. A local date BEFORE the UTC one
     * is therefore not a correction but a zone that disagrees with the instant (a venue west of
     * Greenwich, whose bars the provider already stamps inside the right UTC day), and moving the
     * point backwards there could collide with a day the series already holds.
     */
    fun dateOf(epochSeconds: Long, zone: ZoneId?): LocalDate {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val utc = instant.atZone(ZoneOffset.UTC).toLocalDate()
        if (zone == null) return utc
        val local = instant.atZone(zone).toLocalDate()
        return if (local.isAfter(utc)) local else utc
    }
}
