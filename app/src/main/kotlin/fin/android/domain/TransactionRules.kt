package fin.android.domain

import java.math.BigDecimal
import java.time.LocalDate

/** Write-time guards only: existing authenticated ledger records remain readable unchanged. */
object TransactionRules {
    fun checkValues(kind: TxKind, asset: String?, qty: BigDecimal, amount: BigDecimal) {
        require(amount.signum() >= 0) { "Amount must not be negative" }
        require(qty.signum() >= 0) { "Quantity must not be negative" }
        if (kind == TxKind.buy || kind == TxKind.sell) {
            require(qty.signum() > 0) { "Quantity must be positive for a $kind" }
        }
        if (kind == TxKind.buy || kind == TxKind.sell || kind == TxKind.dividend) {
            require(!asset.isNullOrBlank()) { "An asset is required for a $kind" }
        }
    }

    /** Mirrors Go CheckSecurityStatement: quantity includes same-day trades in this account. */
    fun checkSecurityStatement(book: Book, account: String, asset: String?, date: LocalDate, replacing: String? = null) {
        if (asset == null) return // pure cash statement
        val security = requireNotNull(book.assets[asset]) { "Unknown asset: $asset" }
        if (security.kind != AssetKind.SECURITY) return
        var quantity = BigDecimal.ZERO
        for (tx in book.txs.values) {
            if (tx.id == replacing || tx.account != account || tx.asset != asset || tx.date > date) continue
            when (tx.kind) {
                TxKind.buy -> quantity += tx.qty
                TxKind.sell -> quantity -= tx.qty
                else -> Unit
            }
        }
        require(quantity.signum() > 0) {
            "${book.accounts[account]?.name ?: account} holds no ${security.name} on $date: " +
                "record its buy first, then set its value"
        }
    }
}
