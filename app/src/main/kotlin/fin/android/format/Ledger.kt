package fin.android.format

import fin.android.crypto.B64
import fin.android.crypto.Ids
import fin.android.domain.Account
import fin.android.domain.AccountRules
import fin.android.domain.Asset
import fin.android.domain.AssetRules
import fin.android.domain.Book
import fin.android.domain.Money
import fin.android.domain.TxKind
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDate

/**
 * An opened finador ledger: the validated header, the materialized [book], and (internally) the
 * derived keys and verbatim record entries needed for diff-on-save and merge. Instances are
 * immutable; mutations return a new [Ledger].
 */
class Ledger internal constructor(
    val header: Header,
    internal val keys: Keys,
    internal val entries: List<Entry>,
    val book: Book,
) {
    /** The file's stable id (header), used to name the per-ledger market cache sidecar. */
    val fileId: ByteArray get() = header.id

    /** The cache subkey, for the regenerable market sidecar (never the ledger key). */
    val cacheKey: ByteArray get() = keys.cache

    /** Serializes to the on-disk `.fin` bytes (verbatim record prefix + freshly sealed trailer). */
    fun toBytes(): ByteArray = Writer.serialize(header, keys, entries)

    fun addTransaction(
        date: LocalDate,
        account: String,
        asset: String?,
        kind: TxKind,
        qty: BigDecimal,
        amount: Money,
        note: String? = null,
    ): Ledger = append(listOf(txEnvelope("tx", txDto(Ids.newId(), date, account, asset, kind, qty, amount, note))))

    fun editTransaction(
        id: String,
        date: LocalDate,
        account: String,
        asset: String?,
        kind: TxKind,
        qty: BigDecimal,
        amount: Money,
        note: String? = null,
    ): Ledger = append(listOf(txEnvelope("tx-edit", txDto(id, date, account, asset, kind, qty, amount, note))))

    fun deleteTransaction(id: String): Ledger {
        val d = wireJson.encodeToJsonElement(IdRefDto.serializer(), IdRefDto(id)).jsonObject
        return append(listOf(Envelope("tx-del", Rfc3339.now(), d)))
    }

    /**
     * Upserts an account (an `acct` record, last-writer-wins by id) - used for both create and edit,
     * since the format reconciles by id. Rejects a reference collision against the *current* book
     * (post-pull when called inside [fin.android.remote.Sync.mutate]).
     */
    fun putAccount(account: Account): Ledger {
        AccountRules.checkRefs(book.accounts.values, account)
        val dto = AcctDto(account.id, account.name, account.ccy, account.tax.toWire(), account.aliases)
        return append(listOf(Envelope("acct", Rfc3339.now(), wireJson.encodeToJsonElement(AcctDto.serializer(), dto).jsonObject)))
    }

    /** Deletes an account (an `acct-del` tombstone). Refuses to orphan a referencing transaction. */
    fun deleteAccount(id: String): Ledger {
        AccountRules.assertNoTxRefs(book, id)
        val d = wireJson.encodeToJsonElement(IdRefDto.serializer(), IdRefDto(id)).jsonObject
        return append(listOf(Envelope("acct-del", Rfc3339.now(), d)))
    }

    /**
     * Upserts an asset (an `asset` record, last-writer-wins by id) - used for both create and edit,
     * since the format reconciles by id. Rejects a reference collision against the *current* book
     * (post-pull when called inside [fin.android.remote.Sync.mutate]).
     */
    fun putAsset(asset: Asset): Ledger {
        AssetRules.checkRefs(book.assets.values, asset)
        val dto = AssetDto(
            id = asset.id,
            kind = asset.kind.wire,
            name = asset.name,
            ticker = asset.ticker,
            isin = asset.isin,
            aliases = asset.aliases,
            ccy = asset.ccy,
            group = asset.group,
            withholding = asset.withholding,
        )
        return append(listOf(Envelope("asset", Rfc3339.now(), wireJson.encodeToJsonElement(AssetDto.serializer(), dto).jsonObject)))
    }

    /** Deletes an asset (an `asset-del` tombstone). Refuses to orphan a referencing transaction. */
    fun deleteAsset(id: String): Ledger {
        AssetRules.assertNoTxRefs(book, id)
        val d = wireJson.encodeToJsonElement(IdRefDto.serializer(), IdRefDto(id)).jsonObject
        return append(listOf(Envelope("asset-del", Rfc3339.now(), d)))
    }

    /** Reconciles a diverged copy of the same ledger (union + last-writer-wins by ts). */
    fun merge(other: Ledger, resolve: (Conflict) -> Int = { 0 }): Ledger = Merge.merge(this, other, resolve)

    internal fun append(envs: List<Envelope>): Ledger {
        val newEntries = Writer.append(header, keys, entries, envs)
        return Ledger(header, keys, newEntries, Replay.fold(newEntries))
    }

    private fun txDto(
        id: String, date: LocalDate, account: String, asset: String?,
        kind: TxKind, qty: BigDecimal, amount: Money, note: String?,
    ) = TxDto(
        id = id,
        date = date.toString(),
        account = account,
        asset = asset,
        kind = kind.name,
        qty = qty.toPlainString(),
        amount = MoneyDto(amount.amount.toPlainString(), amount.ccy),
        note = note,
        importHash = null,
    )

    private fun txEnvelope(kind: String, dto: TxDto): Envelope =
        Envelope(kind, Rfc3339.now(), wireJson.encodeToJsonElement(TxDto.serializer(), dto).jsonObject)

    companion object {
        /**
         * Opens and fully verifies a `.fin` byte stream under [passphrase].
         * @throws BadPasswordOrCorruptException wrong passphrase or tampered/corrupt file.
         * @throws UnsupportedFormatException unknown format version or record kind.
         */
        fun open(bytes: ByteArray, passphrase: String): Ledger {
            val raw = Log.open(bytes, passphrase)
            val book = try {
                Replay.fold(raw.entries)
            } catch (e: UnsupportedFormatException) {
                throw e // unknown record kind - must stay distinct
            } catch (e: Exception) {
                // Authenticated but malformed payload (bad decimal/date/enum, missing field): the file
                // is effectively corrupt. Honors the documented @throws instead of leaking a raw JVM exception.
                throw BadPasswordOrCorruptException(e)
            }
            return Ledger(raw.header, raw.keys, raw.entries, book)
        }

        /** Creates a brand-new empty ledger (fresh salt + file id) - first-run / onboarding. */
        fun create(passphrase: String, t: Int = 3, m: Int = 65536): Ledger {
            val rng = SecureRandom()
            val salt = ByteArray(16).also { rng.nextBytes(it) }
            val id = ByteArray(16).also { rng.nextBytes(it) }
            val p = minOf(4, Runtime.getRuntime().availableProcessors())
            val rawLine =
                """{"fmt":"finador-ledger","v":3,"kdf":"argon2id","t":$t,"m":$m,"p":$p,"salt":"${B64.encode(salt)}","id":"${B64.encode(id)}"}"""
            val header = Header.parse(rawLine)
            val keys = deriveKeys(passphrase, header)
            return Ledger(header, keys, emptyList(), Book())
        }
    }
}
