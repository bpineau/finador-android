package fin.android.valuation

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import fin.android.domain.TaxRule
import fin.android.domain.Tx
import fin.android.domain.TxKind
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * Sweeps randomized ledgers against the one invariant the two valuation engines share: the last
 * point of the series behind the chart is what [Valuator] reports at that date. Port of the Go
 * `internal/portfolio/endpoint_fuzz_test.go` (D39, D41).
 *
 * The named tests pin the conventions; this pins the AGREEMENT, which is what silently rots - the
 * two engines replay the same ledger through two different loops ([Valuer] folds, [SeriesBuilder]
 * walks day by day). Seeds deliberately pile several records on the same four days, mix currencies,
 * envelopes, tax rules, properties and securities, and leave trades unpriced: every divergence
 * found so far lived in exactly that corner.
 *
 * Android's series is whole-book and gross-only (the chart shows one curve), so the All scope's
 * gross is what it can pin; the Go harness also sweeps the group and envelope scopes, and the net
 * value each carries.
 */
class EndpointFuzzTest {
    /** Seeds swept. The Go reference runs 20000; this is what keeps the JVM gate a few seconds. */
    private val seeds = 20000

    /** Failures dumped before giving up: enough to see a pattern, few enough to read. */
    private val maxReported = 6

    private val kinds = listOf(
        TxKind.buy, TxKind.sell, TxKind.dividend, TxKind.fee,
        TxKind.deposit, TxKind.withdraw, TxKind.statement,
    )
    private val rules = listOf(
        TaxRule.None,
        TaxRule.Gains(BigDecimal("0.314")),
        TaxRule.Value(BigDecimal("0.20")),
    )
    private val ccys = listOf("EUR", "USD")

    private val from = LocalDate.parse("2026-02-01")
    private val at = LocalDate.parse("2026-02-10")

    // One unit of EUR is worth 1.1 USD from before the window; USD is the constant 1.
    private val fx = mapOf("EUR" to PriceSeries(listOf(PricePoint(LocalDate.parse("2026-01-01"), 1.1))))

    @Test fun valueAndSeriesAgreeOnTheEndpoint() {
        val failures = mutableListOf<String>()
        for (seed in 0 until seeds) {
            if (failures.size >= maxReported) break
            val rng = Random(seed)
            val (book, market) = ledger(rng)
            val want = Valuator.value(book, market, referenceCcy = "EUR", at = at).gross
            val got = SeriesBuilder(book, market, "EUR").build(from, at).points.last().close
            if (abs(got - want) > 0.01) {
                failures += "seed $seed GROSS: Valuator $want vs series $got\n${dump(book, market)}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** One random book: 1-2 envelopes, 1-2 assets, 1-10 records piled on four days, half priced. */
    private fun ledger(rng: Random): Pair<Book, MarketData> {
        val accounts = (0 until 1 + rng.nextInt(2)).map {
            Account("a$it", "a$it", ccys[rng.nextInt(2)], rules[rng.nextInt(rules.size)])
        }.associateBy { it.id }
        val assets = (0 until 1 + rng.nextInt(2)).map {
            val kind = if (rng.nextInt(4) == 0) AssetKind.PROPERTY else AssetKind.SECURITY
            Asset("x$it", kind, "x$it", ccy = ccys[rng.nextInt(2)], group = "g")
        }.associateBy { it.id }

        val txs = (0 until 1 + rng.nextInt(10)).map { i ->
            val kind = kinds[rng.nextInt(kinds.size)]
            val asset = if (kind != TxKind.deposit && kind != TxKind.withdraw && rng.nextInt(5) > 0) {
                assets.keys.elementAt(rng.nextInt(assets.size))
            } else {
                null
            }
            Tx(
                id = "t%02d".format(i),
                date = LocalDate.parse("2026-02-%02d".format(1 + rng.nextInt(4))), // same-day collisions on purpose
                account = accounts.keys.elementAt(rng.nextInt(accounts.size)),
                asset = asset,
                kind = kind,
                qty = BigDecimal(1 + rng.nextInt(9)),
                amount = Money(BigDecimal(100 * (1 + rng.nextInt(9))), ccys[rng.nextInt(2)]),
            )
        }.associateBy { it.id }

        val prices = assets.keys.mapNotNull { id ->
            if (rng.nextInt(2) != 0) return@mapNotNull null
            id to PriceSeries(listOf(PricePoint(LocalDate.parse("2026-02-02"), 10.0 + rng.nextInt(50))))
        }.toMap()

        val book = Book(accounts = accounts, assets = assets, txs = txs, config = mapOf("currency" to "EUR"))
        return book to MarketData(prices = prices, fx = fx)
    }

    /** The failing ledger, in replay order: what a divergence has to be read from. */
    private fun dump(book: Book, market: MarketData): String {
        val sb = StringBuilder()
        for (a in book.accounts.values) sb.append("  acct ${a.id} ccy=${a.ccy} tax=${a.tax}\n")
        for (a in book.assets.values) sb.append("  asset ${a.id} kind=${a.kind} ccy=${a.ccy}\n")
        for (t in book.txs.values.sortedWith(compareBy({ it.date }, { it.id }))) {
            sb.append("  ${t.id} ${t.date} acc=${t.account} ${t.kind} asset=${t.asset} qty=${t.qty} amt=${t.amount}\n")
        }
        for ((id, s) in market.prices) for (p in s.points) sb.append("  px $id ${p.date} ${p.close}\n")
        return sb.toString()
    }
}
