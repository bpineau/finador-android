package fin.android.market

import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.PriceSeries
import java.time.LocalDate

/**
 * Orchestrates a market refresh for a [Book]: fetches a daily series for each security (by ticker
 * then ISIN, via the [MultiSource] chain), the per-currency USD FX series (via Yahoo), and merges
 * them into the existing [MarketData]. Pure of Android — callers handle the encrypted sidecar cache.
 */
object Quotes {
    fun refresh(
        book: Book,
        existing: MarketData?,
        from: LocalDate,
        now: LocalDate,
        multi: MultiSource = MultiSource.default(),
        yahoo: Yahoo = Yahoo(),
    ): MarketData {
        val referenceCcy = book.config["currency"] ?: "EUR"
        val prices = LinkedHashMap(existing?.prices ?: emptyMap())
        val fx = LinkedHashMap(existing?.fx ?: emptyMap())
        val dividends = LinkedHashMap(existing?.dividends ?: emptyMap())
        val currencies = linkedSetOf<String>()

        for (asset in book.assets.values) {
            currencies.add(asset.ccy)
            if (asset.kind != AssetKind.SECURITY) continue
            if (asset.ticker == null && asset.isin == null) continue
            val daily = multi.daily(Ref(asset.ticker, asset.isin), from) ?: continue
            prices[asset.id] = (prices[asset.id] ?: PriceSeries()).merge(daily.closes).copy(fetchedAt = now)
            if (daily.dividends.isNotEmpty()) dividends[asset.id] = daily.dividends
            daily.currency?.let { currencies.add(it) }
        }
        for (account in book.accounts.values) currencies.add(account.ccy)
        currencies.add(referenceCcy)

        for (ccy in currencies) {
            if (ccy == "USD") continue
            val series = yahoo.fxToUsd(ccy, from) ?: continue
            fx[ccy] = (fx[ccy] ?: PriceSeries()).merge(series.points).copy(fetchedAt = now)
        }

        return MarketData(prices, fx, dividends)
    }
}
