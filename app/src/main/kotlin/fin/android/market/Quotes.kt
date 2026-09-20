package fin.android.market

import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import fin.android.domain.TxKind
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Orchestrates a market refresh for a [Book]: fetches a daily series for each security (by ticker
 * then ISIN, via the [MultiSource] chain), the per-currency USD FX series (via Yahoo), then one
 * batched pass of live quotes, and merges everything into the existing [MarketData]. Pure of
 * Android - callers handle the encrypted sidecar cache.
 *
 * The two passes answer two different questions. The daily pass owns history and dividends and is
 * fetched incrementally, from the last cached close rather than from [from], as soon as a series
 * already reaches that far back: re-downloading years of closes per asset on every refresh is what
 * gets a phone throttled, and a throttled fetch silently falls through to end-of-day providers. The
 * spot pass then overwrites today's point with the live market price, which is the only thing that
 * moves during a session.
 *
 * [refreshExtended] adds the extended-hours opt-in: the same passes, plus the off-hours prints of
 * the US-listed lines, collected for display and stored nowhere.
 *
 * A held employee-savings fund ([AirfundFunds]) adds a third concern. Its NAV is published two days
 * late, so both passes are followed by a [Nowcast] estimate read off a listed proxy, which the
 * refresh fetches even when the user holds none of it. Estimates are recomputed from scratch every
 * time: the cached series is stripped of the previous ones before anything merges into it, and the
 * cache sidecar strips them again on the way to disk.
 */
object Quotes {

    /**
     * An off-hours print of one asset: display only, never merged into a series and never cached.
     * [ticker] and [ccy] are the asset's declared ones (the quote passed the currency contract),
     * [time] the instant the print was struck (epoch seconds) and [session] "pre" or "post".
     * [regularTime] is the instant of the regular print observed in the same pass, which is what
     * makes [currentAt] able to re-check the freshness the print was accepted on.
     */
    data class OffHoursPrint(
        val ticker: String,
        val ccy: String,
        val price: Double,
        val time: Long,
        val session: String,
        val regularTime: Long,
    ) {
        /** Whether this print still prices a screen at [now] (see [Session.stillCurrent]). */
        fun currentAt(now: Instant): Boolean =
            Session.stillCurrent(Session.Print(price, time, session), regularTime, now)
    }

    /**
     * The prints of [offHours] that still price a screen at [now]: an observed print has a
     * validity, and a screen outlives the refresh that took it (see [Session.stillCurrent]). Every
     * consumer of [Refresh.offHours] must read them through this, and with the clock it displays
     * with - a valuation carrying a print the venue has moved past is a wrong total with a caption.
     */
    fun current(offHours: Map<String, OffHoursPrint>, now: Instant): Map<String, OffHoursPrint> =
        offHours.filterValues { it.currentAt(now) }

    /**
     * What a refresh observed: the [market] to store (exactly what a plain [refresh] produces),
     * the [offHours] prints to SHOW, keyed by asset id, and the [warnings] to surface - a source
     * that restated its history is the one event a holder must act on, since a split moves the
     * position too and only a ledger record can fix that.
     */
    data class Refresh(
        val market: MarketData,
        val offHours: Map<String, OffHoursPrint>,
        val warnings: List<String> = emptyList(),
    )

    fun refresh(
        book: Book,
        existing: MarketData?,
        from: LocalDate,
        now: LocalDate,
        referenceCcy: String? = null,
        multi: MultiSource = MultiSource.default(),
        yahoo: Yahoo = Yahoo(),
    ): MarketData = refreshDetailed(book, existing, from, now, referenceCcy, multi, yahoo, extendedHours = false).market

    /**
     * [refresh] with the extended-hours opt-in (parity with the Go reference's
     * `SpotRefreshExtended`, behind finador's `value --extended`): a venue's pre-market or
     * after-hours print is collected for display when it is newer than the regular session's last
     * price.
     *
     * Such a print is DISPLAYED, NEVER STORED: it is a thinner trade than a close, and its instant
     * belongs to a session the persisted daily series does not model (an after-hours print in New
     * York already falls on the next civil day in Paris). It travels in [Refresh.offHours] alone,
     * and [Refresh.market] is byte-for-byte what the same refresh without the opt-in would have
     * returned - so no caller, and no cache, can persist it by accident.
     *
     * An employee-savings fund ([AirfundFunds]) never takes a session: its price is an estimate read
     * off a proxy's REGULAR print, and it is not a spot target at all.
     */
    fun refreshExtended(
        book: Book,
        existing: MarketData?,
        from: LocalDate,
        now: LocalDate,
        referenceCcy: String? = null,
        multi: MultiSource = MultiSource.default(),
        yahoo: Yahoo = Yahoo(),
    ): Refresh = refreshDetailed(book, existing, from, now, referenceCcy, multi, yahoo, extendedHours = true)

    /**
     * The one implementation behind [refresh] and [refreshExtended], with the extended-hours
     * opt-in as a parameter and the whole [Refresh] as its answer. What the app itself calls: it
     * must store the market, show the prints AND surface the warnings, whichever mode it is in.
     */
    fun refreshDetailed(
        book: Book,
        existing: MarketData?,
        from: LocalDate,
        now: LocalDate,
        referenceCcy: String? = null,
        multi: MultiSource = MultiSource.default(),
        yahoo: Yahoo = Yahoo(),
        extendedHours: Boolean = false,
    ): Refresh {
        // The effective display currency must have its FX series fetched too, so a value/gain
        // shown in a non-book currency can be converted. Falls back to the book's, then EUR.
        val refCcy = referenceCcy?.trim()?.uppercase()?.ifBlank { null } ?: book.config["currency"] ?: "EUR"
        // Yesterday's estimates go first: a nowcast stands on the proxy of the moment, never on
        // itself, and the published NAV it stood in for may well have landed since.
        val prices = LinkedHashMap((existing?.prices ?: emptyMap()).mapValues { it.value.withoutEstimates() })
        val fx = LinkedHashMap(existing?.fx ?: emptyMap())
        val dividends = LinkedHashMap(existing?.dividends ?: emptyMap())
        val currencies = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        // asset id → (declared ticker, declared currency), the spot pass's targets.
        val spotTargets = LinkedHashMap<String, Pair<String, String>>()
        // asset id → the employee-savings fund it is, the nowcast's targets. Kept apart from
        // spotTargets because no quote provider covers an FCPE: its live price is an estimate.
        val fundTargets = LinkedHashMap<String, AirfundFund>()

        for (asset in book.assets.values) {
            currencies.add(asset.ccy)
            if (asset.kind != AssetKind.SECURITY) continue
            if (asset.ticker == null && asset.isin == null) continue
            val fund = AirfundFunds.byTicker(asset.ticker)
            if (fund != null) {
                fundTargets[asset.id] = fund
                currencies.add(fund.proxyCcy) // the proxy's own currency must be convertible
            } else {
                asset.ticker?.let { spotTargets[asset.id] = it to asset.ccy }
            }
            var daily = multi.daily(Ref(asset.ticker, asset.isin), fetchFrom(prices[asset.id], from)) ?: continue
            // The declared currency is the contract here exactly as it is on the spot pass below: a
            // series served in another currency (an FT twin listing) would be merged into a series
            // the valuation reads as the declared one, and persisted. Skipping leaves `fetchedAt`
            // unstamped, so a later run tries again (mirrors Go).
            //
            // The comparison goes through [Units.same], never a bare `!=`: a venue SUB-UNIT is the
            // same money as its currency (the provider has already rescaled the numbers), and a
            // London line answering "GBp" for a GBP holding is a match, not a mismatch. Reading it
            // as one is what used to leave such a holding at its cost basis for ever.
            if (!Units.same(daily.currency, asset.ccy)) continue
            // A source that RESTATES its history - a share split, a currency redenomination, a
            // class merge - answers the overlap day with a different close. The fetch above is
            // incremental (it starts at the last cached point), so merging such an answer would
            // leave the old scale in front of the new one: a permanent cliff the valuation, the
            // chart and the TWR all read as a session that never happened (after a 4:1 split,
            // -75%). Rebuild the whole series from the source instead (Go D40).
            var cached = prices[asset.id]
            val restatement = restated(cached, daily.closes)
            if (restatement != null) {
                val label = asset.ticker ?: asset.isin ?: asset.name
                val full = multi.daily(Ref(asset.ticker, asset.isin), from)
                if (full == null || !Units.same(full.currency, asset.ccy)) {
                    warnings += "$label: the source restated its history (split or redenomination) " +
                        "and the deep re-fetch failed: quotes ignored"
                    continue
                }
                // Name the event when it looks like a split, and say which quantities the ledger
                // owes: the series is now split-adjusted over its whole history and the ledger is
                // not, so the position reads at 1/N of reality until a record fixes it (Go D47).
                // The advice comes FIRST, since the holder is shown one message.
                splitAdvice(book, asset.id, label, restatement)?.let { warnings += it }
                warnings += "$label: history restated by the source on ${restatement.on} " +
                    "(split or redenomination) - series rebuilt from $from; check the ledger quantities"
                cached = null
                daily = full
            }
            prices[asset.id] = (cached ?: PriceSeries()).merge(daily.closes).copy(fetchedAt = now)
            if (daily.dividends.isNotEmpty()) {
                // Upsert by ex-date (mirror Go's mergeDividends): an incremental fetch returns only a
                // recent window, so overwriting would drop previously-cached historical dividends.
                val byDate = LinkedHashMap<LocalDate, DividendEvent>()
                for (d in dividends[asset.id].orEmpty()) byDate[d.exDate] = d
                for (d in daily.dividends) byDate[d.exDate] = d
                dividends[asset.id] = byDate.values.sortedBy { it.exDate }
            }
            daily.currency?.let { currencies.add(it) }
        }
        for (account in book.accounts.values) currencies.add(account.ccy)
        // A currency reaches the book a THIRD way: on the record itself. A fee charged in JPY, a
        // deposit made in CHF, a dividend paid in USD on a euro line, a statement declaring a
        // balance - each is money the valuation has to cross, and collecting only the accounts and
        // the assets left those amounts with no rate, hence counted as zero (Go D43).
        for (tx in book.txs.values) currencies.add(tx.amount.ccy)
        currencies.add(refCcy)

        // The nowcast proxies. Fetched whether or not the user holds them, since standing in for a
        // fund's unpublished days is all they are here for; cached under a reserved key.
        val proxies = fundTargets.values.distinctBy { it.proxy }
        // proxy symbol → its sessions' open-to-close ratios, for the funds struck at the open.
        // Held for this pass only: an estimate is never cached, and neither is what anchors it.
        val openFactors = LinkedHashMap<String, PriceSeries>()
        for (fund in proxies) {
            val key = Nowcast.proxyKey(fund.proxy)
            // A fund struck at the open needs the proxy's opening print of its last NAV's day, a
            // couple of business days back, which an incremental window starting at the last cached
            // close would not reach. Widening it costs no extra call, only a few closes to merge.
            var start = fetchFrom(prices[key], from)
            if (fund.navAnchor == NavAnchor.OPEN) start = minOf(start, now.minusDays(ANCHOR_WINDOW_DAYS))
            val daily = multi.daily(Ref(fund.proxy, null), start) ?: continue
            // Same contract: the proxy's declared currency is what the nowcast converts FROM.
            if (!Units.same(daily.currency, fund.proxyCcy)) continue
            prices[key] = (prices[key] ?: PriceSeries()).merge(daily.closes).copy(fetchedAt = now)
            if (daily.openFactors.isNotEmpty()) openFactors[fund.proxy] = PriceSeries(daily.openFactors)
        }

        // The rate is needed AT THE DATE the record carries, not today: a historical deposit is
        // crossed at the rate of its own day. The FX floor therefore reaches a week before the
        // oldest record of the book, whatever window the caller asked for (Go D43).
        val fxFloor = fxHistoryFloor(book, from)
        for (ccy in currencies) {
            if (ccy == "USD") continue
            val series = yahoo.fxToUsd(ccy, fetchFrom(fx[ccy], fxFloor)) ?: continue
            fx[ccy] = (fx[ccy] ?: PriceSeries()).merge(series.points).copy(fetchedAt = now)
        }

        // The published NAVs are in; estimate the days the funds have not priced yet.
        val converter = Converter(fx)
        for ((assetId, fund) in fundTargets) {
            val series = prices[assetId] ?: continue
            prices[assetId] = Nowcast
                .forward(series, prices[Nowcast.proxyKey(fund.proxy)], fund, converter, openFactors[fund.proxy])
        }

        // One batched call for every live price: securities, nowcast proxies and FX alike.
        val fxSymbols = currencies.filter { it != "USD" }.associateBy({ "${it}USD=X" }, { it })
        val quotes = yahoo.quotes(
            spotTargets.values.map { it.first } + proxies.map { it.proxy } + fxSymbols.keys,
            extendedHours = extendedHours,
        )
        val offHours = LinkedHashMap<String, OffHoursPrint>()
        for ((assetId, target) in spotTargets) {
            val (ticker, ccy) = target
            val q = quotes[ticker] ?: continue
            // The declared currency is the contract: a quote from a twin listing in another
            // currency is dropped, never spliced into a series denominated in the first one.
            if (!Units.same(q.currency, ccy)) continue
            // The off-hours print is collected apart and merged nowhere; the regular one below is
            // the only thing that reaches the series, opt-in or not.
            q.offHours?.let {
                offHours[assetId] = OffHoursPrint(ticker, ccy, it.price, it.time, it.session, q.time)
            }
            prices[assetId] = (prices[assetId] ?: PriceSeries())
                .merge(listOf(PricePoint(dayOf(q), q.price))).copy(fetchedAt = now)
        }
        for ((symbol, ccy) in fxSymbols) {
            val q = quotes[symbol] ?: continue
            if (!Units.same(q.currency, "USD")) continue // FX series hold USD per unit
            fx[ccy] = (fx[ccy] ?: PriceSeries())
                .merge(listOf(PricePoint(dayOf(q), q.price))).copy(fetchedAt = now)
        }

        // The proxies' own live prices, then the funds' estimated ones. The proxy point lands
        // first so the next refresh anchors on the freshest close the app has.
        for (fund in proxies) {
            val q = quotes[fund.proxy] ?: continue
            if (!Units.same(q.currency, fund.proxyCcy)) continue
            val key = Nowcast.proxyKey(fund.proxy)
            prices[key] = (prices[key] ?: PriceSeries())
                .merge(listOf(PricePoint(dayOf(q), q.price))).copy(fetchedAt = now)
        }
        // Rebuilt after the FX spot pass, so the fallback rate is the freshest one the app holds.
        val liveConverter = Converter(fx)
        for ((assetId, fund) in fundTargets) {
            val series = prices[assetId] ?: continue
            val q = quotes[fund.proxy] ?: continue
            if (!Units.same(q.currency, fund.proxyCcy)) continue
            val rate = Nowcast.liveRate(quotes, fund.proxyCcy, fund.ccy)
            prices[assetId] = Nowcast
                .live(series, prices[Nowcast.proxyKey(fund.proxy)], fund, q, rate, liveConverter, openFactors[fund.proxy])
                .copy(fetchedAt = now)
        }

        return Refresh(MarketData(prices, fx, dividends), offHours, warnings)
    }

    /**
     * How far a re-served close may sit from the cached one before the history counts as restated:
     * 2 %, far above a provider correcting a close to the cent and far below the smallest share
     * split (3:2, -33 %). A false positive costs one deep download and identical data, which is why
     * the threshold is low.
     */
    private const val RESTATED_TOLERANCE = 0.02

    /**
     * Whether [incoming] contradicts the [cached] series on a date both cover - the signature of a
     * source that re-scaled its history. It compares the FIRST shared date: an incremental fetch
     * starts at the last cached point, so that date is the junction the merge would glue.
     *
     * [cached] is read after [PriceSeries.withoutEstimates] has run over the whole cache, so a
     * nowcast tail - an estimate of a day the fund had not published yet - is never here to be
     * mistaken for a restatement: only published closes are compared.
     */
    private fun restated(cached: PriceSeries?, incoming: List<PricePoint>): Restatement? {
        if (cached == null || cached.points.isEmpty()) return null
        for (p in incoming) {
            val i = cached.points.binarySearchBy(p.date) { it.date }
            if (i < 0) continue
            val was = cached.points[i].close
            if (was <= 0 || p.close <= 0) return null
            if (abs(p.close - was) <= RESTATED_TOLERANCE * was) return null
            return Restatement(factor = was / p.close, on = p.date)
        }
        return null
    }

    /** A measured restatement: the [factor] the source applied (cached close divided by the
     *  re-served one) and the overlap [on] date it was measured on. A 4:1 split serves every close
     *  at a quarter, so the factor IS the ratio. */
    internal data class Restatement(val factor: Double, val on: LocalDate)

    /** A split ratio, as it is written ("4:1", "1:10" for a reverse split) and as the multiplier
     *  the LEDGER quantities owe. */
    internal data class SplitRatio(val label: String, val quantity: BigDecimal)

    /**
     * The share splits a restatement factor is matched against, as (new shares, old shares): a 4:1
     * split multiplies the quantity by 4 and divides the price by 4. Reverses are the same list
     * inverted. Anything else - a currency redenomination, a class merge, a provider rewriting a
     * stretch of closes - matches nothing, and nothing is then claimed. Mirrors the Go reference.
     */
    private val SPLIT_RATIOS = listOf(
        2 to 1, 3 to 1, 4 to 1, 5 to 1, 6 to 1, 7 to 1, 8 to 1, 10 to 1, 15 to 1, 20 to 1,
        50 to 1, 100 to 1, 3 to 2, 4 to 3, 5 to 2, 5 to 3, 5 to 4, 7 to 2, 7 to 5, 9 to 5,
    )

    /** How far the measured factor may sit from a ratio and still be named: 1 %, which absorbs
     *  closes rounded to the cent while leaving the ratios far apart (the closest pair is 1.25 and
     *  1.333). */
    private const val SPLIT_RATIO_TOLERANCE = 0.01

    /** Matches a restatement [factor] against the usual split ratios; null when it looks like no
     *  split at all. */
    internal fun splitRatioFor(factor: Double): SplitRatio? {
        if (factor <= 0 || factor.isNaN() || factor.isInfinite()) return null
        for ((n, d) in SPLIT_RATIOS) {
            val direct = n.toDouble() / d
            if (abs(factor - direct) <= SPLIT_RATIO_TOLERANCE * direct) {
                return SplitRatio("$n:$d", BigDecimal(n).divide(BigDecimal(d), MathContext.DECIMAL64))
            }
            val inverse = d.toDouble() / n
            if (abs(factor - inverse) <= SPLIT_RATIO_TOLERANCE * inverse) {
                return SplitRatio("$d:$n", BigDecimal(d).divide(BigDecimal(n), MathContext.DECIMAL64))
            }
        }
        return null
    }

    /**
     * The sentence a measured restatement earns when it looks like a split, or null. A split moves
     * the POSITION as well as the price, and nothing but a ledger record moves the position: the
     * source has already re-scaled its whole history, so every trade of the asset predating the
     * restatement owes the same re-scaling - quantities multiplied, amounts untouched (Go D47).
     */
    private fun splitAdvice(book: Book, assetId: String, label: String, r: Restatement): String? {
        val ratio = splitRatioFor(r.factor) ?: return null
        val head = "$label: the factor is ${trimRatio(r.factor)}, a ${ratio.label} split"
        val owed = book.txs.values
            .filter { it.asset == assetId && (it.kind == TxKind.buy || it.kind == TxKind.sell) && it.date.isBefore(r.on) }
            .sortedWith(compareBy({ it.date }, { it.id }))
            .map { "${it.kind} of ${it.date}: ${it.qty.stripTrailingZeros().toPlainString()} becomes " +
                (it.qty * ratio.quantity).stripTrailingZeros().toPlainString() }
        if (owed.isEmpty()) {
            return "$head - no trade of this security predates it, so no quantity to restate"
        }
        return "$head - the ledger still holds the pre-split quantities; restate them " +
            "(edit the quantity, leave the amount alone): ${owed.joinToString("; ")}"
    }

    /** Renders a measured factor without a trailing-zero tail. */
    private fun trimRatio(f: Double): String =
        BigDecimal(f).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * The earliest date an FX series must cover: a week before the oldest record of the book, since
     * any record may be denominated in any currency. Never later than the caller's own [floor].
     */
    private fun fxHistoryFloor(book: Book, floor: LocalDate): LocalDate {
        val oldest = book.txs.values.minOfOrNull { it.date } ?: return floor
        return minOf(floor, oldest.minusDays(7))
    }

    /**
     * Where to start the daily fetch: from the last cached close once the series already reaches
     * back to [floor], from [floor] otherwise. A series whose own history starts after [floor] (a
     * recent listing) can never satisfy that test, so it refetches in full - which is cheap, since
     * that is exactly the case with few closes to send.
     */
    private fun fetchFrom(cached: PriceSeries?, floor: LocalDate): LocalDate {
        val first = cached?.points?.firstOrNull()?.date ?: return floor
        if (first.isAfter(floor)) return floor
        return cached.points.last().date
    }

    /**
     * How far back a proxy's daily window is widened for a fund struck at the OPEN: enough to hold
     * the opening print of the last published NAV's day, which lands about two business days late
     * (a long week-end plus a holiday is the worst case this covers).
     */
    private const val ANCHOR_WINDOW_DAYS = 14L

    /**
     * The civil day a live quote belongs to: the venue's own, never UTC ([VenueDay]). A print
     * struck at 10:30 in Sydney falls on the day BEFORE in UTC, and a spot point dated a day early
     * lands beside the daily bar of the same session instead of on it.
     */
    private fun dayOf(q: Quote): LocalDate =
        VenueDay.dateOf(q.time, VenueDay.zoneOf(q.symbol, q.zone))
}
