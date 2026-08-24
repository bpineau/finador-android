package fin.android.market

import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import java.time.LocalDate

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
 * A held employee-savings fund ([AirfundFunds]) adds a third concern. Its NAV is published two days
 * late, so both passes are followed by a [Nowcast] estimate read off a listed proxy, which the
 * refresh fetches even when the user holds none of it. Estimates are recomputed from scratch every
 * time: the cached series is stripped of the previous ones before anything merges into it, and the
 * cache sidecar strips them again on the way to disk.
 */
object Quotes {
    fun refresh(
        book: Book,
        existing: MarketData?,
        from: LocalDate,
        now: LocalDate,
        referenceCcy: String? = null,
        multi: MultiSource = MultiSource.default(),
        yahoo: Yahoo = Yahoo(),
    ): MarketData {
        // The effective display currency must have its FX series fetched too, so a value/gain
        // shown in a non-book currency can be converted. Falls back to the book's, then EUR.
        val refCcy = referenceCcy?.trim()?.uppercase()?.ifBlank { null } ?: book.config["currency"] ?: "EUR"
        // Yesterday's estimates go first: a nowcast stands on the proxy of the moment, never on
        // itself, and the published NAV it stood in for may well have landed since.
        val prices = LinkedHashMap((existing?.prices ?: emptyMap()).mapValues { it.value.withoutEstimates() })
        val fx = LinkedHashMap(existing?.fx ?: emptyMap())
        val dividends = LinkedHashMap(existing?.dividends ?: emptyMap())
        val currencies = linkedSetOf<String>()

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
            val daily = multi.daily(Ref(asset.ticker, asset.isin), fetchFrom(prices[asset.id], from)) ?: continue
            prices[asset.id] = (prices[asset.id] ?: PriceSeries()).merge(daily.closes).copy(fetchedAt = now)
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
        currencies.add(refCcy)

        // The nowcast proxies. Fetched whether or not the user holds them, since standing in for a
        // fund's unpublished days is all they are here for; cached under a reserved key.
        val proxies = fundTargets.values.distinctBy { it.proxy }
        for (fund in proxies) {
            val key = Nowcast.proxyKey(fund.proxy)
            val daily = multi.daily(Ref(fund.proxy, null), fetchFrom(prices[key], from)) ?: continue
            prices[key] = (prices[key] ?: PriceSeries()).merge(daily.closes).copy(fetchedAt = now)
        }

        for (ccy in currencies) {
            if (ccy == "USD") continue
            val series = yahoo.fxToUsd(ccy, fetchFrom(fx[ccy], from)) ?: continue
            fx[ccy] = (fx[ccy] ?: PriceSeries()).merge(series.points).copy(fetchedAt = now)
        }

        // The published NAVs are in; estimate the days the funds have not priced yet.
        val converter = Converter(fx)
        for ((assetId, fund) in fundTargets) {
            val series = prices[assetId] ?: continue
            prices[assetId] = Nowcast.forward(series, prices[Nowcast.proxyKey(fund.proxy)], fund, converter)
        }

        // One batched call for every live price: securities, nowcast proxies and FX alike.
        val fxSymbols = currencies.filter { it != "USD" }.associateBy({ "${it}USD=X" }, { it })
        val quotes = yahoo.quotes(
            spotTargets.values.map { it.first } + proxies.map { it.proxy } + fxSymbols.keys,
        )
        for ((assetId, target) in spotTargets) {
            val (ticker, ccy) = target
            val q = quotes[ticker] ?: continue
            // The declared currency is the contract: a quote from a twin listing in another
            // currency is dropped, never spliced into a series denominated in the first one.
            if (q.currency != null && q.currency != ccy) continue
            prices[assetId] = (prices[assetId] ?: PriceSeries())
                .merge(listOf(PricePoint(dateOf(q.time), q.price))).copy(fetchedAt = now)
        }
        for ((symbol, ccy) in fxSymbols) {
            val q = quotes[symbol] ?: continue
            if (q.currency != null && q.currency != "USD") continue // FX series hold USD per unit
            fx[ccy] = (fx[ccy] ?: PriceSeries())
                .merge(listOf(PricePoint(dateOf(q.time), q.price))).copy(fetchedAt = now)
        }

        // The proxies' own live prices, then the funds' estimated ones. The proxy point lands
        // first so the next refresh anchors on the freshest close the app has.
        for (fund in proxies) {
            val q = quotes[fund.proxy] ?: continue
            if (q.currency != null && q.currency != fund.proxyCcy) continue
            val key = Nowcast.proxyKey(fund.proxy)
            prices[key] = (prices[key] ?: PriceSeries())
                .merge(listOf(PricePoint(dateOf(q.time), q.price))).copy(fetchedAt = now)
        }
        // Rebuilt after the FX spot pass, so the fallback rate is the freshest one the app holds.
        val liveConverter = Converter(fx)
        for ((assetId, fund) in fundTargets) {
            val series = prices[assetId] ?: continue
            val q = quotes[fund.proxy] ?: continue
            if (q.currency != null && q.currency != fund.proxyCcy) continue
            val rate = Nowcast.liveRate(quotes, fund.proxyCcy, fund.ccy)
            prices[assetId] = Nowcast
                .live(series, prices[Nowcast.proxyKey(fund.proxy)], fund, q, rate, liveConverter)
                .copy(fetchedAt = now)
        }

        return MarketData(prices, fx, dividends)
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

    /** The civil day of an epoch-second instant, in UTC - the convention the whole client uses. */
    private fun dateOf(epochSeconds: Long): LocalDate =
        java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).toLocalDate()
}
