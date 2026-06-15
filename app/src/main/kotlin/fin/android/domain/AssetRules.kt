package fin.android.domain

/**
 * Asset integrity rules, mirroring the Go `domain.Book` guards
 * ([checkRefs] ↔ `CheckAssetRefs`, [assertNoTxRefs] ↔ `RemoveAsset`). They throw
 * [IllegalArgumentException] on violation so callers surface a clean message instead of writing a
 * record that would poison resolution or orphan a transaction.
 */
object AssetRules {

    /**
     * Rejects [candidate] when its id, ticker, isin, name, or any alias collides case-insensitively
     * with a *different* asset. The asset sharing [candidate]'s id is skipped, so re-saving an edited
     * asset never collides with itself.
     */
    fun checkRefs(assets: Collection<Asset>, candidate: Asset) {
        val refs = refsOf(candidate)
        for (other in assets) {
            if (other.id == candidate.id) continue
            val others = refsOf(other)
            for (r in refs) {
                if (others.any { it.equals(r, ignoreCase = true) }) {
                    throw IllegalArgumentException("reference \"$r\" already used by ${other.id}")
                }
            }
        }
    }

    private fun refsOf(a: Asset): List<String> =
        (listOf(a.id, a.ticker.orEmpty(), a.isin.orEmpty(), a.name) + a.aliases).filter { it.isNotBlank() }

    /** Rejects deleting [assetId] while any transaction still references it. */
    fun assertNoTxRefs(book: Book, assetId: String) {
        val tx = book.txs.values.firstOrNull { it.asset == assetId } ?: return
        throw IllegalArgumentException(
            "asset $assetId is referenced by transaction ${tx.id} — delete its transactions first",
        )
    }
}
