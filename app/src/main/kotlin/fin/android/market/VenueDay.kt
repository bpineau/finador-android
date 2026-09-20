package fin.android.market

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
     * A CROSS IS EXEMPT on purpose. It has no exchange and no trading day, and the zone a provider
     * attaches to one is decorative. Its dating also carries a separate, known anomaly (in the
     * European summer a cross has no Friday-dated close and one dated on the Sunday before) which
     * re-dating by a time zone would not fix but hide: the Sunday point would land on a Monday the
     * series already holds, and the later value would overwrite the earlier one - a lost session,
     * which is worse than a misplaced one. The anomaly stands, named, as it does in the Go reference.
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
    private fun isCross(symbol: String): Boolean = symbol.endsWith("=X")

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
