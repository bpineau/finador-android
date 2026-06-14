// Package market fetches and converts public market data: daily closes, dividends and FX, behind a
// pluggable chain of providers.
package fin.android.market

import fin.android.domain.DividendEvent
import fin.android.domain.PricePoint
import java.time.LocalDate

/**
 * Identifies an instrument to quote. Ticker-based providers (Yahoo) use [symbol]; fund providers
 * (Financial Times, Morningstar) use [isin]. A given fetch may carry both: each provider picks the
 * field it understands.
 */
data class Ref(val symbol: String?, val isin: String?)

/** Daily market data for one instrument: quotation [currency] (may be null), [closes] and [dividends]. */
data class DailyData(
    val currency: String?,
    val closes: List<PricePoint>,
    val dividends: List<DividendEvent> = emptyList(),
)

/**
 * Supplies a daily series for the [Ref]s it understands. [daily] returns null when the ref falls
 * outside the provider's scope (Go's ErrNotCovered) or yields nothing, so the [MultiSource] chain can
 * fall through to the next provider. Providers catch their own IO/parse errors internally and return
 * null rather than throwing.
 */
interface Provider {
    fun daily(ref: Ref, from: LocalDate): DailyData?
    val name: String
}
