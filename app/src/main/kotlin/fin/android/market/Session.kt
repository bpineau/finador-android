package fin.android.market

/**
 * The trading sessions a [Quote] can name, and the rule that picks between them.
 *
 * A faithful port of the Go reference's `freshestSession`
 * (`pofo/pkg/marketdata/session.go`, behind finador's `value --extended`): the same 9-case table
 * gates it in [SessionTest].
 */
object Session {
    const val REGULAR = "regular"
    const val PRE = "pre"
    const val POST = "post"

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
}
