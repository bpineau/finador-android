package fin.android.market

import java.time.LocalDate

/**
 * Quotes a daily series by trying an ordered chain of providers: the first that returns a non-null
 * result with a non-empty close series wins. The default chain mirrors the Go implementation -
 * Yahoo for ticker symbols, with Financial Times then Morningstar as ISIN fallbacks for funds
 * Yahoo lacks. Airfund leads: it answers for a handful of employee-savings funds no other
 * provider covers at all, and returns null instantly for every other ref.
 * Chain: Airfund → Yahoo → FT → Morningstar.
 */
class MultiSource(private val providers: List<Provider>) {

    /**
     * The first provider's answer, with any venue SUB-UNIT already removed ([Units.normalize]).
     * Each provider normalizes its own numbers; doing it again here is free (the call is
     * idempotent) and makes this the one door a provider added later cannot walk a pence price
     * through.
     */
    fun daily(ref: Ref, from: LocalDate): DailyData? {
        for (p in providers) {
            val d = p.daily(ref, from)
            if (d != null && d.closes.isNotEmpty()) return Units.normalize(d)
        }
        return null
    }

    companion object {
        fun default(): MultiSource = MultiSource(listOf(Airfund(), Yahoo(), Ft(), Morningstar()))
    }
}
