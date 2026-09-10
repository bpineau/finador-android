package fin.android.data

import fin.android.domain.Book
import fin.android.market.FxRate
import fin.android.market.Quotes
import fin.android.remote.SyncState
import fin.android.valuation.AssetDetail
import fin.android.valuation.GainsReport
import fin.android.valuation.PerfMetrics
import fin.android.valuation.Valuation

/** Top-level app state the UI renders. */
sealed interface AppState {
    /** Determining configuration/secrets. */
    data object Loading : AppState

    /** No GitHub repo / token / passphrase configured yet - show onboarding. */
    data object Onboarding : AppState

    /** Configured; awaiting biometric unlock before secrets are used. */
    data object Locked : AppState

    /** Unlocked: the valued portfolio, ready to view and mutate. */
    data class Ready(
        val valuation: Valuation,
        val perf: PerfMetrics?,
        val gains: GainsReport?,
        val book: Book,
        val sync: SyncState,
        val message: String?,
        val refreshing: Boolean,
        /** Per-asset detail pages, precomputed so opening one is instant. Keyed by asset id. */
        val assetDetails: Map<String, AssetDetail> = emptyMap(),
        /**
         * The exchange rates the valuation crossed through, one per foreign currency held, sorted
         * by code. Empty when the whole book is denominated in the reference currency.
         */
        val fxRates: List<FxRate> = emptyList(),
        /**
         * The off-hours prints the valuation was priced at, keyed by asset id: empty unless the
         * extended-hours setting is on AND a venue really served a fresher pre/post-market print.
         * Shown labelled next to the line and never stored (see `Quotes.refreshExtended`).
         */
        val offHours: Map<String, Quotes.OffHoursPrint> = emptyMap(),
    ) : AppState
}
