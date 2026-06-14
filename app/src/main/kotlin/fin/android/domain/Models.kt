package fin.android.domain

import java.math.BigDecimal
import java.time.LocalDate

data class Account(
    val id: String,
    val name: String,
    val ccy: String,
    val tax: TaxRule,
    val aliases: List<String> = emptyList(),
)

data class Asset(
    val id: String,
    val kind: AssetKind,
    val name: String,
    val ticker: String? = null,
    val isin: String? = null,
    val aliases: List<String> = emptyList(),
    val ccy: String,
    val group: String? = null,
    val withholding: Double? = null,
)

data class Tx(
    val id: String,
    val date: LocalDate,
    val account: String,
    val asset: String? = null,
    val kind: TxKind,
    val qty: BigDecimal,
    val amount: Money,
    val note: String? = null,
    val importHash: String? = null,
)

data class Label(
    val id: String,
    val account: String,
    val asset: String,
    val name: String,
)

/** Materialized ledger state after folding the record log. */
data class Book(
    val accounts: Map<String, Account> = emptyMap(),
    val assets: Map<String, Asset> = emptyMap(),
    val txs: Map<String, Tx> = emptyMap(),
    val labels: Map<String, Label> = emptyMap(),
    val config: Map<String, String> = emptyMap(),
)
