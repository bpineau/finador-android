package fin.android.valuation

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.TaxRule
import fin.android.domain.Tx
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Parity tests for the unquoted-security fallbacks, ported from the Go
 * `portfolio/unquoted_test.go`: a security with no market data is valued at its
 * last statement (a NAV observation, scaled per share when the quantity changed
 * since), else at its cost basis - a bought position is never worth 0 just
 * because nothing observed it yet, or the buy itself would read as a loss.
 */
class UnquotedTest {
    private val tol = 1e-9

    private fun d(s: String) = LocalDate.parse(s)
    private fun eur(s: String) = Money(BigDecimal(s), "EUR")

    private var seq = 0
    private fun tx(
        date: String, account: String, asset: String?, kind: TxKind,
        qty: String = "0", amount: Money,
    ) = Tx(
        id = "tx-%04d".format(seq++),
        date = d(date),
        account = account,
        asset = asset,
        kind = kind,
        qty = BigDecimal(qty),
        amount = amount,
    )

    /**
     * The Go `unquotedFundBook` fixture: buy an unquoted fund, then value it with a
     * statement. [tracked] toggles whether the account carries cash.
     */
    private fun unquotedFundBook(tracked: Boolean): Book {
        seq = 0
        val txs = mutableMapOf<String, Tx>()
        val list = buildList {
            if (tracked) add(tx("2026-01-01", "cto", null, TxKind.deposit, amount = eur("10000")))
            add(tx("2026-01-10", "cto", "fund", TxKind.buy, "10", eur("4000")))
            add(tx("2026-02-10", "cto", "fund", TxKind.statement, amount = eur("4200")))
        }
        list.forEach { txs[it.id] = it }
        return Book(
            accounts = mapOf("cto" to Account("cto", "CTO Meridia", "EUR", TaxRule.None)),
            assets = mapOf("fund" to Asset("fund", AssetKind.SECURITY, "FCPE Fund", ccy = "EUR")),
            txs = txs,
            config = mapOf("currency" to "EUR"),
        )
    }

    private fun valueAt(series: Series, date: String): Double =
        series.points.first { it.date == d(date) }.close

    /**
     * Go `TestSeriesUnquotedBuyThenStatement`: a buy is never a gain NOR a loss -
     * a bought security with no quote is valued at cost until observed, so the buy
     * day is value-neutral; the first statement is a NAV observation (performance),
     * never a second adoption.
     */
    @Test fun seriesUnquotedBuyThenStatement() {
        data class Case(val tracked: Boolean, val wantFlows: Int, val wantTwr: Double)
        for (tc in listOf(
            Case(tracked = false, wantFlows = 1, wantTwr = 4200.0 / 4000 - 1),
            Case(tracked = true, wantFlows = 0, wantTwr = 10200.0 / 10000 - 1),
        )) {
            val book = unquotedFundBook(tc.tracked)
            val series = SeriesBuilder(book, MarketData(), "EUR").build(d("2026-01-01"), d("2026-03-01"))
            assertEquals("flows (tracked=${tc.tracked})", tc.wantFlows, series.flows.size)
            assertEquals("TWR (tracked=${tc.tracked})", tc.wantTwr, Perf.twr(series.points, series.flows), tol)

            // The buy day must not move the measured value (cost fallback).
            if (tc.tracked) {
                assertEquals("value across the buy day", valueAt(series, "2026-01-09"), valueAt(series, "2026-01-10"), tol)
            } else {
                assertEquals("buy day value = cost", 4000.0, valueAt(series, "2026-01-10"), tol)
            }
        }
    }

    /**
     * Go `TestValueMatchesSeriesOnUnquotedFallbacks`: Value() must agree with the
     * end of Series() on the statement-per-share and cost fallbacks; after selling
     * 5 of the 10 shares observed at 4200, the position is worth 5 × 420 = 2100,
     * not the stale 4200 total.
     */
    @Test fun valueMatchesSeriesOnUnquotedFallbacks() {
        val book = unquotedFundBook(tracked = true).let { b ->
            val sell = tx("2026-02-20", "cto", "fund", TxKind.sell, "5", eur("2100"))
            b.copy(txs = b.txs + (sell.id to sell))
        }

        for (at in listOf("2026-01-15", "2026-02-15", "2026-03-01")) {
            val want = Valuator.value(book, MarketData(), at = d(at)).gross
            val series = SeriesBuilder(book, MarketData(), "EUR").build(d("2026-01-01"), d(at))
            assertEquals("gross at $at", want, series.points.last().close, tol)
        }

        val fund = Valuator.value(book, MarketData(), at = d("2026-03-01"))
            .positions.first { it.assetId == "fund" }
        assertEquals("per-share statement after sell", 2100.0, fund.gross, tol)
    }
}
