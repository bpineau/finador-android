package fin.android.ui

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionEntryTest {
    private val account = Account("account", "CTO Meridia", "USD", TaxRule.None)
    private val asset = Asset("asset", AssetKind.SECURITY, "Synthetic Fund", ccy = "USD")
    private fun validate(kind: TxKind, qty: String, amount: String, selected: Asset? = asset) =
        validateTransaction(account, kind, selected, "2026-06-01", qty, amount, "USD")

    @Test fun rejectsNegativeAmountAndNonpositiveTradeQuantityBeforeSaving() {
        assertNotNull(validate(TxKind.buy, "-2", "10"))
        assertNotNull(validate(TxKind.buy, "0", "10"))
        assertNotNull(validate(TxKind.sell, "0", "10"))
        assertNotNull(validate(TxKind.deposit, "", "-10", null))
        assertNotNull(validate(TxKind.dividend, "", "10", null))
        assertNull(validate(TxKind.buy, "2", "0"))
        assertNull(validate(TxKind.statement, "", "0", null))
    }
}
