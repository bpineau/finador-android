package fin.android.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RFC 3339 with nanoseconds in UTC, formatted exactly like Go's time.RFC3339Nano (trailing zeros
 * of the fraction trimmed; no fraction when zero). Keeping the same shape as the reference writer
 * makes the `ts` field — the merge last-writer-wins key — compare consistently across clients.
 */
internal object Rfc3339 {
    private val secs = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    fun now(): String = format(Instant.now())

    fun format(instant: Instant): String {
        val head = secs.format(instant)
        val nanos = instant.atOffset(ZoneOffset.UTC).nano
        if (nanos == 0) return "${head}Z"
        val frac = "%09d".format(nanos).trimEnd('0')
        return "$head.${frac}Z"
    }
}
