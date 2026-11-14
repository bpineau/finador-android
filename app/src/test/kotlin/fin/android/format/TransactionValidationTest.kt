package fin.android.format

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Money
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionValidationTest {
    private val day = LocalDate.parse("2026-06-01")
    private fun ledger() = Ledger.create("validation", t = 1, m = 8)
        .putAccount(Account("account", "CTO Meridia", "USD", TaxRule.None))
        .putAccount(Account("other", "CTO Zephyr", "USD", TaxRule.None))
        .putAsset(Asset("asset", AssetKind.SECURITY, "Synthetic Fund", ticker = "GTWR", ccy = "USD"))
        .putAsset(Asset("property", AssetKind.PROPERTY, "Synthetic Property", ccy = "USD"))

    private fun add(l: Ledger, kind: TxKind, qty: String = "1", amount: String = "10", asset: String? = "asset",
                    date: LocalDate = day, account: String = "account") =
        l.addTransaction(date, account, asset, kind, BigDecimal(qty), Money(BigDecimal(amount), "USD"))

    @Test fun invalidAmountsQuantitiesAndMissingTradeAssetsAreRejectedOnAddAndEdit() {
        data class Case(val kind: TxKind, val qty: String, val amount: String, val asset: String? = "asset")
        val cases = TxKind.entries.map { Case(it, "1", "-1") } +
            TxKind.entries.map { Case(it, "-1", "1") } +
            listOf(Case(TxKind.buy, "0", "1"), Case(TxKind.sell, "0", "1")) +
            listOf(TxKind.buy, TxKind.sell, TxKind.dividend).map { Case(it, "1", "1", null) }
        val original = add(ledger(), TxKind.buy)
        val id = original.book.txs.keys.single()
        for (c in cases) {
            assertThrows(c.toString(), IllegalArgumentException::class.java) {
                add(original, c.kind, c.qty, c.amount, c.asset)
            }
            assertThrows(c.toString(), IllegalArgumentException::class.java) {
                original.editTransaction(id, day, "account", c.asset, c.kind,
                    BigDecimal(c.qty), Money(BigDecimal(c.amount), "USD"))
            }
        }
        assertEquals(TxKind.buy, original.book.txs.getValue(id).kind)
        assertEquals(1, Ledger.open(original.toBytes(), "validation").book.txs.size)
    }

    @Test fun zeroAmountsAndNonTradeZeroQuantitiesRemainValid() {
        var l = add(ledger(), TxKind.buy, amount = "0") // free shares
        for (kind in TxKind.entries.filter { it != TxKind.buy && it != TxKind.sell }) {
            l = add(l, kind, qty = "0", amount = "0", asset =
                if (kind == TxKind.statement || kind == TxKind.dividend) "asset" else null)
        }
        assertEquals(6, l.book.txs.size)
    }

    @Test fun securityStatementNeedsPositiveQuantityInItsAccountAtItsDate() {
        val original = ledger()
        assertThrows(IllegalArgumentException::class.java) { add(original, TxKind.statement, qty = "0") }
        val bought = add(original, TxKind.buy, qty = "2", date = day.plusDays(1))
        assertThrows(IllegalArgumentException::class.java) { add(bought, TxKind.statement, qty = "0") }
        assertThrows(IllegalArgumentException::class.java) {
            add(bought, TxKind.statement, qty = "0", date = day.plusDays(1), account = "other")
        }
        val stated = add(bought, TxKind.statement, qty = "0", date = day.plusDays(1))
        assertEquals(2, stated.book.txs.size) // same-day buys count, as in Go
        val sold = add(bought, TxKind.sell, qty = "2", date = day.plusDays(2))
        assertThrows(IllegalArgumentException::class.java) {
            add(sold, TxKind.statement, qty = "0", date = day.plusDays(2))
        }
        add(original, TxKind.statement, qty = "0", asset = "property")
        add(original, TxKind.statement, qty = "0", asset = null)
    }

    @Test fun editingTheOnlyBuyIntoAStatementCannotUseItsOldQuantity() {
        val original = add(ledger(), TxKind.buy)
        val id = original.book.txs.keys.single()
        assertThrows(IllegalArgumentException::class.java) {
            original.editTransaction(id, day, "account", "asset", TxKind.statement,
                BigDecimal.ZERO, Money(BigDecimal.TEN, "USD"))
        }
        assertEquals(TxKind.buy, original.book.txs.getValue(id).kind)
    }
}
