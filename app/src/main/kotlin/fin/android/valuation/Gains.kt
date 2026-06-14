// Package-internal "gains" layer: period portfolio P&L and a per-asset gains
// table. It is a thin, additive consumer of the existing valuation/perf engine:
//   - portfolio relative gain  = the flow-neutralized TWR of [Perf] over the window;
//   - portfolio absolute gain  = (V(today) − V(then)) − netExternalFlows,
//                                using the SAME daily value series and dated flows
//                                that [SeriesBuilder] already separates;
//   - per-asset absolute gain  = qtyNow × (priceRef(today) − priceRef(then)), a
//                                price/FX-move approximation on the CURRENT holding.
//
// Nothing here changes existing semantics; it only reads the engine.
package fin.android.valuation

import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.market.Converter
import java.math.BigDecimal
import java.time.LocalDate

/** One portfolio period figure. [relative] is a fraction (TWR), null if undefined. */
data class PeriodGain(val label: String, val absolute: Double, val relative: Double?)

/**
 * One per-security row. Each value is the ABSOLUTE gain in the reference currency
 * of the CURRENT holding due to the price + FX move over the period (null when a
 * price or FX rate is missing at either endpoint). See [Gains.report] for the
 * intra-window-quantity caveat.
 */
data class AssetGain(
    val assetId: String,
    val name: String,
    val ccy: String,
    val d1: Double?,
    val d7: Double?,
    val m1: Double?,
    val y1: Double?,
)

/** One per-asset period figure for the detail page: a % and an absolute, both in the ref ccy. */
data class AssetPeriodGain(val label: String, val relative: Double?, val absolute: Double) // ref ccy

/**
 * The single-asset detail the [AssetDetailScreen] renders. A simplified, price/FX-move view of
 * one held security: current holding, current ref-ccy value, period %/absolute gains, and a short
 * ref-ccy price history for a sparkline. No TWR/XIRR/CAGR/Sharpe — pure price math.
 */
data class AssetDetail(
    val assetId: String,
    val name: String,
    val ticker: String?,
    val isin: String?,
    val assetCcy: String,
    val referenceCcy: String,
    val qty: BigDecimal,
    val price: Double?, // native-ccy close at `today` (current MARKET price)
    val value: Double, // value in ref ccy (now)
    val accounts: List<String>, // account names holding it
    val periods: List<AssetPeriodGain>, // 1d,3d,5d,7d,1m,6m,1y,YTD
    val priceHistory: List<PricePoint>, // full ref-ccy price history, for the sparkline + range selector
    // Cost basis (average-cost), in the reference currency. Populated for gains-taxed envelopes;
    // null for value-taxed / untaxed accounts where the engine tracks no basis.
    val costBasis: Double? = null, // total cost basis, ref ccy
    val avgBuyPrice: Double? = null, // costBasis / qty, ref ccy per unit
    val unrealized: Double? = null, // value − costBasis, ref ccy
    val unrealizedPct: Double? = null, // unrealized / costBasis, fraction
)

/** The full report the UI renders. */
data class GainsReport(
    val referenceCcy: String,
    val periods: List<PeriodGain>,
    val assets: List<AssetGain>,
)

object Gains {
    /** Portfolio periods, in display order: label → the day `then` is computed from `today`. */
    private fun portfolioWindows(today: LocalDate): List<Pair<String, LocalDate>> = listOf(
        "1d" to today.minusDays(1),
        "3d" to today.minusDays(3),
        "5d" to today.minusDays(5),
        "7d" to today.minusDays(7),
        "1m" to today.minusMonths(1),
        "6m" to today.minusMonths(6),
        "1y" to today.minusYears(1),
        "YTD" to LocalDate.of(today.year, 1, 1),
    )

    /**
     * Builds the [GainsReport] of [book] against [market] in [referenceCcy]
     * (defaults to `book.config["currency"]` then "EUR") as of [today].
     *
     * Per-asset gains use the asset's CURRENT quantity: they approximate the gain
     * as a pure price/FX move on today's holding. Intra-window quantity changes
     * (buys/sells inside the period) are NOT attributed — for an exact, flow-aware
     * figure use the portfolio-level absolute (which neutralizes external flows).
     */
    fun report(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        today: LocalDate,
    ): GainsReport {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"

        val periods = portfolioWindows(today).map { (label, then) ->
            periodGain(book, market, ccy, label, then, today)
        }
        val assets = assetGains(book, market, ccy, today)
        return GainsReport(referenceCcy = ccy, periods = periods, assets = assets)
    }

    // ---- portfolio periods ----

    /**
     * One portfolio window `[then, today]`:
     *   - absolute = (V(today) − V(then)) − Σ external flows over (then, today].
     *     [SeriesBuilder] yields V as the daily value series and the dated external
     *     flows it separates from market moves; summing those flows neutralizes
     *     deposits/withdraws/onboardings so only the market P&L remains.
     *   - relative = the flow-neutralized TWR over the same window (null when the
     *     window has < 2 points or the TWR is otherwise undefined).
     */
    private fun periodGain(
        book: Book,
        market: MarketData,
        ccy: String,
        label: String,
        then: LocalDate,
        today: LocalDate,
    ): PeriodGain {
        if (!then.isBefore(today)) return PeriodGain(label, 0.0, null)
        val series = SeriesBuilder(book, market, ccy).build(then, today)
        val pts = series.points
        if (pts.size < 2) return PeriodGain(label, 0.0, null)

        val vThen = pts.first().close
        val vToday = pts.last().close
        val netFlows = series.flows.sumOf { it.amount }
        val absolute = (vToday - vThen) - netFlows

        // TWR is defined once two points exist; reuse the engine for parity.
        val relative = Perf.twr(pts, series.flows)
        return PeriodGain(label, absolute, relative)
    }

    // ---- per-asset table ----

    private fun assetGains(
        book: Book,
        market: MarketData,
        ccy: String,
        today: LocalDate,
    ): List<AssetGain> {
        val positions = Valuator.value(book, market, referenceCcy = ccy, at = today)
            .positions.filter { it.kind == "security" && it.qty.signum() > 0 }

        val converter = Converter(market.fx)
        val rows = positions.mapNotNull { p ->
            val assetId = p.assetId ?: return@mapNotNull null
            val asset = book.assets[assetId] ?: return@mapNotNull null
            val qtyNow = p.qty.toDouble()
            fun gain(then: LocalDate): Double? =
                assetGain(market, converter, assetId, asset.ccy, ccy, qtyNow, then, today)
            val name = asset.ticker ?: asset.name
            AssetGain(
                assetId = assetId,
                name = name,
                ccy = asset.ccy,
                d1 = gain(today.minusDays(1)),
                d7 = gain(today.minusDays(7)),
                m1 = gain(today.minusMonths(1)),
                y1 = gain(today.minusYears(1)),
            )
        }
        // Stable order: largest |1y| first, ties broken by name. Rows with a null
        // 1y sink below the priced ones (treated as 0 magnitude) but keep name order.
        return rows.sortedWith(
            compareByDescending<AssetGain> { kotlin.math.abs(it.y1 ?: 0.0) }.thenBy { it.name },
        )
    }

    /**
     * Absolute gain in [ref] of `qtyNow` shares due to the price + FX move between
     * [then] and [today]:  qtyNow × (priceRef(today) − priceRef(then)).
     * `priceRef(d) = close(d) × fx(assetCcy→ref, d)`; null if either close or rate
     * is missing at an endpoint.
     */
    private fun assetGain(
        market: MarketData,
        converter: Converter,
        assetId: String,
        assetCcy: String,
        ref: String,
        qtyNow: Double,
        then: LocalDate,
        today: LocalDate,
    ): Double? {
        val priceThen = priceRef(market, converter, assetId, assetCcy, ref, then) ?: return null
        val priceToday = priceRef(market, converter, assetId, assetCcy, ref, today) ?: return null
        return qtyNow * (priceToday - priceThen)
    }

    /** One share's price in [ref] at [d] (forward-filled close × FX); null if missing. */
    private fun priceRef(
        market: MarketData,
        converter: Converter,
        assetId: String,
        assetCcy: String,
        ref: String,
        d: LocalDate,
    ): Double? {
        val close = market.prices[assetId]?.at(d)?.first ?: return null
        val rate = converter.rate(assetCcy, ref, d) ?: return null
        return close * rate
    }

    // ---- per-asset detail page ----

    /** Detail-page period windows, in display order: label → the day `then` is computed from `today`. */
    private fun assetWindows(today: LocalDate): List<Pair<String, LocalDate>> = listOf(
        "1d" to today.minusDays(1),
        "3d" to today.minusDays(3),
        "5d" to today.minusDays(5),
        "7d" to today.minusDays(7),
        "1m" to today.minusMonths(1),
        "6m" to today.minusMonths(6),
        "1y" to today.minusYears(1),
        "YTD" to LocalDate.of(today.year, 1, 1),
    )

    /**
     * Builds the simplified [AssetDetail] for [assetId] as of [today], in [referenceCcy] (defaults
     * to `book.config["currency"]` then "EUR"). Only period % increase + absolute gain are computed
     * — no TWR/XIRR/CAGR/Sharpe. Returns null when the asset is not a currently-held security.
     *
     * Per period: relative = priceRef(today)/priceRef(then) − 1 (null if either endpoint price/FX
     * is missing); absolute = qtyNow × (priceRef(today) − priceRef(then)). priceRef(d) is the asset's
     * native close at d × FX(assetCcy→ref, d).
     */
    fun assetDetail(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        today: LocalDate,
        assetId: String,
    ): AssetDetail? {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"
        val asset = book.assets[assetId] ?: return null

        // Held-security positions of this asset (today's valuation), with their account names.
        val positions = Valuator.value(book, market, referenceCcy = ccy, at = today)
            .positions.filter { it.kind == "security" && it.assetId == assetId }
        val qtyNow = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.qty }
        if (qtyNow.signum() <= 0) return null // not a held security
        val accounts = positions.map { it.accountName }.distinct()

        val converter = Converter(market.fx)
        val qty = qtyNow.toDouble()
        val priceNow = market.prices[assetId]?.at(today)?.first
        val valueRefToday = priceRef(market, converter, assetId, asset.ccy, ccy, today)
        val value = if (valueRefToday != null) qty * valueRefToday else 0.0

        val periods = assetWindows(today).map { (label, then) ->
            val pThen = priceRef(market, converter, assetId, asset.ccy, ccy, then)
            val pToday = valueRefToday
            val relative = if (pThen != null && pToday != null && pThen != 0.0) pToday / pThen - 1 else null
            val absolute = if (pThen != null && pToday != null) qty * (pToday - pThen) else 0.0
            AssetPeriodGain(label, relative, absolute)
        }

        // Full ref-ccy price history (the detail screen's range selector filters it), skipping any
        // day missing an FX rate.
        val series = market.prices[assetId]?.points ?: emptyList()
        val priceHistory = series.mapNotNull { pt ->
            val rate = converter.rate(asset.ccy, ccy, pt.date) ?: return@mapNotNull null
            PricePoint(pt.date, pt.close * rate)
        }

        // Cost basis: sum the per-position bases the engine tracked (gains-taxed envelopes). If none
        // of this asset's positions carry a basis, leave it null (shown as "—").
        val bases = positions.mapNotNull { it.costBasis }
        val costBasis = if (bases.isEmpty()) null else bases.sum()
        val avgBuyPrice = if (costBasis != null && qty > 0.0) costBasis / qty else null
        val unrealized = if (costBasis != null) value - costBasis else null
        val unrealizedPct = if (costBasis != null && costBasis != 0.0) (value - costBasis) / costBasis else null

        return AssetDetail(
            assetId = assetId,
            name = asset.name,
            ticker = asset.ticker,
            isin = asset.isin,
            assetCcy = asset.ccy,
            referenceCcy = ccy,
            qty = qtyNow,
            price = priceNow,
            value = value,
            accounts = accounts,
            periods = periods,
            priceHistory = priceHistory,
            costBasis = costBasis,
            avgBuyPrice = avgBuyPrice,
            unrealized = unrealized,
            unrealizedPct = unrealizedPct,
        )
    }
}
