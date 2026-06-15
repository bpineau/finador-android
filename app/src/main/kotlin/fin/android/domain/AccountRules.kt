package fin.android.domain

/**
 * Account integrity rules, mirroring the Go `domain.Book` guards
 * ([checkRefs] ↔ `CheckAccountRefs`, [assertNoTxRefs] ↔ `RemoveAccount`). They throw
 * [IllegalArgumentException] on violation so callers surface a clean message instead of writing a
 * record that would poison resolution or orphan a transaction.
 */
object AccountRules {

    /**
     * Rejects [candidate] when its id, name, or any alias collides case-insensitively with a
     * *different* account. The account sharing [candidate]'s id is skipped, so re-saving an edited
     * account never collides with itself.
     */
    fun checkRefs(accounts: Collection<Account>, candidate: Account) {
        val refs = (listOf(candidate.id, candidate.name) + candidate.aliases).filter { it.isNotBlank() }
        for (other in accounts) {
            if (other.id == candidate.id) continue
            val others = (listOf(other.id, other.name) + other.aliases).filter { it.isNotBlank() }
            for (r in refs) {
                if (others.any { it.equals(r, ignoreCase = true) }) {
                    throw IllegalArgumentException("reference \"$r\" already used by ${other.id}")
                }
            }
        }
    }

    /** Rejects deleting [accountId] while any transaction still references it. */
    fun assertNoTxRefs(book: Book, accountId: String) {
        val tx = book.txs.values.firstOrNull { it.account == accountId } ?: return
        throw IllegalArgumentException(
            "account $accountId is referenced by transaction ${tx.id} — delete its transactions first",
        )
    }
}
