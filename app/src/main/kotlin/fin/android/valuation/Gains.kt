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

import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.TxKind
import fin.android.market.Converter
import java.math.BigDecimal
import java.time.LocalDate

/** One portfolio period figure. [relative] is a fraction (TWR), null if undefined. */
data class PeriodGain(val label: String, val absolute: Double, val relative: Double?)

/**
 * One consolidated per-security row for the overview table. [value] is the asset's
 * current GROSS value in the reference currency, summed across every account that
 * holds it (before tax, at the current rate); it drives the table's descending sort.
 * [d1] is the 1-day price/FX move as a fraction, null when a price or FX rate is
 * missing at either endpoint.
 */
data class AssetGain(
    val assetId: String,
    val name: String,
    val value: Double,
    val d1: Double?,
)

/** One per-asset period figure for the detail page: a % and an absolute, both in the ref ccy. */
data class AssetPeriodGain(val label: String, val relative: Double?, val absolute: Double) // ref ccy

/**
 * The single-asset detail the [AssetDetailScreen] renders. A simplified, price/FX-move view of
 * one held security: current holding, current ref-ccy value, period %/absolute gains, and a short
 * ref-ccy price history for a sparkline. No TWR/XIRR/CAGR/Sharpe - pure price math.
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
    val costBasis: Double? = null, // total cost basis / purchase value, ref ccy
    val avgBuyPrice: Double? = null, // costBasis / qty, ref ccy per unit (securities only)
    val unrealized: Double? = null, // value − costBasis, ref ccy
    val unrealizedPct: Double? = null, // unrealized / costBasis, fraction
    val kind: String = "security", // "security" | "property"
    val taxRule: String? = null, // applicable tax envelope(s), e.g. "gains:30%"
    val valuations: List<AssetValuation> = emptyList(), // dated declared values (property statements)
)

/** One dated declared value of an asset (a statement), shown as native amount. */
data class AssetValuation(val date: java.time.LocalDate, val amount: Double, val ccy: String)

/** The full report the UI renders. */
data class GainsReport(
    val referenceCcy: String,
    val periods: List<PeriodGain>,
    val assets: List<AssetGain>,
)

object Gains {
    /**
     * The "as of" date for period windows: the most recent settled close at or before [today]
     * across the book's priced securities. Calendar `today` routinely runs ahead of the last
     * real session (a shut exchange, a pre-open morning). Anchoring windows on `today` then makes
     * "1d" compare a stale, forward-filled close against yesterday while a fresh 24/5 FX point
     * drifts underneath - noise, not a session. Anchoring on the last close makes "1d" mean
     * "last close vs the previous close", matching Yahoo/Google. Falls back to [today] when no
     * security has a close (e.g. a property-only book).
     */
    private fun closeAnchor(market: MarketData, today: LocalDate): LocalDate =
        market.prices.values.mapNotNull { it.at(today)?.second }.maxOrNull() ?: today

    /** Per-security "as of" date: that asset's own last close at or before [today] (else [today]). */
    private fun assetAnchor(market: MarketData, assetId: String, today: LocalDate): LocalDate =
        market.prices[assetId]?.at(today)?.second ?: today

    /** Portfolio periods, in display order: label → the day `then` is computed from the anchor. */
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
     * The per-asset table consolidates each security across accounts into one row
     * carrying its current gross value (ref ccy, before tax) and its 1-day move,
     * sorted by value descending. The portfolio period figures neutralize external
     * flows (deposits/withdraws are not gains).
     */
    fun report(
        book: Book,
        market: MarketData,
        referenceCcy: String? = null,
        today: LocalDate,
    ): GainsReport {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"

        // Windows end at the last settled close, not calendar `today` - see [closeAnchor].
        val anchor = closeAnchor(market, today)
        val periods = portfolioWindows(anchor).map { (label, then) ->
            periodGain(book, market, ccy, label, then, anchor)
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
        // One row per asset: sum the gross value across every account holding it, and
        // take the asset's own 1-day move (a price/FX % on its last settled close - so
        // it is the same whichever account the shares sit in).
        val rows = positions.groupBy { it.assetId }.mapNotNull { (id, ps) ->
            val assetId = id ?: return@mapNotNull null
            val asset = book.assets[assetId] ?: return@mapNotNull null
            val value = ps.sumOf { it.gross }
            val anchor = assetAnchor(market, assetId, today)
            val pAnchor = priceRef(market, converter, assetId, asset.ccy, ccy, anchor)
            val pThen = priceRef(market, converter, assetId, asset.ccy, ccy, anchor.minusDays(1))
            val d1 = if (pAnchor != null && pThen != null && pThen != 0.0) pAnchor / pThen - 1 else null
            AssetGain(assetId = assetId, name = asset.ticker ?: asset.name, value = value, d1 = d1)
        }
        // Largest holding first; ties broken by name.
        return rows.sortedWith(compareByDescending<AssetGain> { it.value }.thenBy { it.name })
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
     * - no TWR/XIRR/CAGR/Sharpe. Returns null when the asset is not a currently-held security.
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
        allPositions: List<Position>? = null,
    ): AssetDetail? {
        val ccy = referenceCcy ?: book.config["currency"] ?: "EUR"
        val asset = book.assets[assetId] ?: return null

        // Callers that already have today's valuation (e.g. precomputing every asset detail) pass its
        // positions to avoid re-folding the book once per asset.
        val valuationPositions = allPositions ?: Valuator.value(book, market, referenceCcy = ccy, at = today).positions
        val converter = Converter(market.fx)

        // Applicable tax envelope(s) and the dated declared values (statements) - for any kind.
        val taxRule = valuationPositions
            .filter { it.assetId == assetId }
            .mapNotNull { book.accounts[it.accountId]?.tax?.toWire() }
            .distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
        val stmts = book.txs.values
            .filter { it.kind == TxKind.statement && it.asset == assetId }
            .sortedBy { it.date }
        val valuations = stmts.map { AssetValuation(it.date, it.amount.amount.toDouble(), it.amount.ccy) }

        if (asset.kind == AssetKind.PROPERTY) {
            val positions = valuationPositions.filter { it.kind == "property" && it.assetId == assetId }
            if (positions.isEmpty()) return null // declared but unvalued
            val value = positions.sumOf { it.gross }
            val bases = positions.mapNotNull { it.costBasis }
            // Purchase / initial value: the engine's basis if tracked, else the first declared value.
            val purchaseRef = when {
                bases.isNotEmpty() -> bases.sum()
                stmts.isNotEmpty() -> stmts.first().let { converter.convert(it.amount.amount.toDouble(), it.amount.ccy, ccy, it.date) }
                else -> null
            }
            val unrealized = if (purchaseRef != null) value - purchaseRef else null
            val unrealizedPct = if (purchaseRef != null && purchaseRef != 0.0) (value - purchaseRef) / purchaseRef else null
            return AssetDetail(
                assetId = assetId, name = asset.name, ticker = asset.ticker, isin = asset.isin,
                assetCcy = asset.ccy, referenceCcy = ccy,
                qty = positions.fold(BigDecimal.ZERO) { a, p -> a + p.qty },
                price = null, value = value, accounts = positions.map { it.accountName }.distinct(),
                periods = emptyList(), priceHistory = emptyList(),
                costBasis = purchaseRef, avgBuyPrice = null, unrealized = unrealized, unrealizedPct = unrealizedPct,
                kind = "property", taxRule = taxRule, valuations = valuations,
            )
        }

        // ---- security ----
        val positions = valuationPositions.filter { it.kind == "security" && it.assetId == assetId }
        val qtyNow = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.qty }
        if (qtyNow.signum() <= 0) return null // not a held security
        val accounts = positions.map { it.accountName }.distinct()
        val qty = qtyNow.toDouble()
        val priceNow = market.prices[assetId]?.at(today)?.first
        val valueRefToday = priceRef(market, converter, assetId, asset.ccy, ccy, today)
        val value = if (valueRefToday != null) qty * valueRefToday else 0.0

        // Period math ends at the asset's last settled close (see [closeAnchor]); the headline
        // value/price above stay live at `today`, matching the Yahoo model (live price, close-to-
        // close day change).
        val anchor = assetAnchor(market, assetId, today)
        val pAnchor = priceRef(market, converter, assetId, asset.ccy, ccy, anchor)
        val periods = assetWindows(anchor).map { (label, then) ->
            val pThen = priceRef(market, converter, assetId, asset.ccy, ccy, then)
            val relative = if (pThen != null && pAnchor != null && pThen != 0.0) pAnchor / pThen - 1 else null
            val absolute = if (pThen != null && pAnchor != null) qty * (pAnchor - pThen) else 0.0
            AssetPeriodGain(label, relative, absolute)
        }
        val series = market.prices[assetId]?.points ?: emptyList()
        val priceHistory = series.mapNotNull { pt ->
            val rate = converter.rate(asset.ccy, ccy, pt.date) ?: return@mapNotNull null
            PricePoint(pt.date, pt.close * rate)
        }
        val bases = positions.mapNotNull { it.costBasis }
        val costBasis = if (bases.isEmpty()) null else bases.sum()
        val avgBuyPrice = if (costBasis != null && qty > 0.0) costBasis / qty else null
        val unrealized = if (costBasis != null) value - costBasis else null
        val unrealizedPct = if (costBasis != null && costBasis != 0.0) (value - costBasis) / costBasis else null

        return AssetDetail(
            assetId = assetId, name = asset.name, ticker = asset.ticker, isin = asset.isin,
            assetCcy = asset.ccy, referenceCcy = ccy, qty = qtyNow, price = priceNow, value = value,
            accounts = accounts, periods = periods, priceHistory = priceHistory,
            costBasis = costBasis, avgBuyPrice = avgBuyPrice, unrealized = unrealized, unrealizedPct = unrealizedPct,
            kind = "security", taxRule = taxRule, valuations = valuations,
        )
    }
}
