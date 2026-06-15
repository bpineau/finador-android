package fin.android.format

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Book
import fin.android.domain.Label
import fin.android.domain.Money
import fin.android.domain.Tx
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Folds the record log in file (append) order into a materialized [Book]: upsert/tombstone by id
 * (config by key). An unknown kind is a hard error - never silently skipped.
 */
internal object Replay {
    fun fold(entries: List<Entry>): Book {
        val accounts = LinkedHashMap<String, Account>()
        val assets = LinkedHashMap<String, Asset>()
        val txs = LinkedHashMap<String, Tx>()
        val labels = LinkedHashMap<String, Label>()
        val config = LinkedHashMap<String, String>()

        for (e in entries) {
            val d = e.env.d
            when (e.env.k) {
                "acct" -> dec(AcctDto.serializer(), d).let {
                    accounts[it.id] = Account(it.id, it.name, it.ccy, TaxRule.fromWire(it.tax), it.aliases)
                }
                "acct-del" -> accounts.remove(dec(IdRefDto.serializer(), d).id)
                "asset" -> dec(AssetDto.serializer(), d).let {
                    assets[it.id] = Asset(it.id, AssetKind.fromWire(it.kind), it.name, it.ticker, it.isin, it.aliases, it.ccy, it.group, it.withholding)
                }
                "asset-del" -> assets.remove(dec(IdRefDto.serializer(), d).id)
                "config" -> dec(CfgDto.serializer(), d).let { config[it.key] = it.value }
                "tx", "tx-edit" -> dec(TxDto.serializer(), d).let {
                    txs[it.id] = Tx(
                        id = it.id,
                        date = LocalDate.parse(it.date),
                        account = it.account,
                        asset = it.asset,
                        kind = TxKind.fromWire(it.kind),
                        qty = BigDecimal(it.qty),
                        amount = Money(BigDecimal(it.amount.amount), it.amount.ccy),
                        note = it.note,
                        importHash = it.importHash,
                    )
                }
                "tx-del" -> txs.remove(dec(IdRefDto.serializer(), d).id)
                "label" -> dec(LabelDto.serializer(), d).let {
                    labels[it.id] = Label(it.id, it.account, it.asset, it.name)
                }
                "label-del" -> labels.remove(dec(IdRefDto.serializer(), d).id)
                else -> throw UnsupportedFormatException("unknown record kind: ${e.env.k}")
            }
        }
        return Book(accounts, assets, txs, labels, config)
    }

    private fun <T> dec(ser: DeserializationStrategy<T>, d: JsonObject): T = wireJson.decodeFromJsonElement(ser, d)
}
