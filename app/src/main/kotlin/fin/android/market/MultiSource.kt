package fin.android.market

import java.time.LocalDate

/**
 * Quotes a daily series by trying an ordered chain of providers: the first that returns a non-null
 * result with a non-empty close series wins. The default chain mirrors the Go implementation -
 * Yahoo for ticker symbols, with Financial Times then Morningstar (via Boursorama) as ISIN fallbacks
 * for funds Yahoo lacks. Chain: Yahoo → FT → Morningstar.
 */
class MultiSource(private val providers: List<Provider>) {

    fun daily(ref: Ref, from: LocalDate): DailyData? {
        for (p in providers) {
            val d = p.daily(ref, from)
            if (d != null && d.closes.isNotEmpty()) return d
        }
        return null
    }

    companion object {
        fun default(): MultiSource = MultiSource(listOf(Yahoo(), Ft(), Morningstar()))
    }
}
