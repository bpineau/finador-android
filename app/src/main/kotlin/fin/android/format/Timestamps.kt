package fin.android.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RFC 3339 with nanoseconds in UTC, formatted exactly like Go's time.RFC3339Nano (trailing zeros
 * of the fraction trimmed; no fraction when zero). Keeping the same shape as the reference writer
 * makes the `ts` field - the merge last-writer-wins key - compare consistently across clients.
 *
 * Locale.ROOT everywhere: this string is sealed into the file and used as the LWW sort key, so the
 * digits must be ASCII 0-9 regardless of the device locale (an Arabic/Persian locale would otherwise
 * emit non-Latin digits and corrupt the timestamp).
 */
internal object Rfc3339 {
    private val secs =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun now(): String = format(Instant.now())

    /**
     * Parses a `ts` into an instant for chronological ordering. A plain string compare of
     * RFC3339Nano is NOT chronological: a whole second renders without a fractional part
     * ("…03Z"), and 'Z' > '.', so it would sort after a later fractional instant of the same
     * second - the last-writer-wins merge could silently elect the older write. A malformed ts
     * (never produced by a conforming writer) collapses to the epoch, keeping the order
     * deterministic.
     */
    fun instant(ts: String): Instant = try {
        Instant.parse(ts)
    } catch (_: Exception) {
        Instant.EPOCH
    }

    fun format(instant: Instant): String {
        val head = secs.format(instant)
        val nanos = instant.atOffset(ZoneOffset.UTC).nano
        if (nanos == 0) return "${head}Z"
        val frac = String.format(Locale.ROOT, "%09d", nanos).trimEnd('0')
        return "$head.${frac}Z"
    }
}
