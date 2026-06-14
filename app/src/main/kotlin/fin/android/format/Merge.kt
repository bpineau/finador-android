package fin.android.format

import kotlinx.serialization.json.jsonPrimitive

/** A true tie surfaced to the user: the same entity edited at the same ts with different payloads. */
data class Conflict(
    val entityClass: String,
    val id: String,
    val ts: String,
    val candidates: List<String>,
)

/**
 * Reconciles two copies of the same ledger that diverged on two devices. Distinct entities union
 * with no loss; concurrent edits of one entity resolve last-writer-wins by ts; a true tie (same
 * entity, equal ts, different payload) is handed to [resolve], which returns the index to keep.
 * The result is re-sealed as a fresh chained log, each winner's original ts preserved.
 */
internal object Merge {
    private data class Tagged(val env: Envelope, val fromOther: Boolean)

    fun merge(base: Ledger, other: Ledger, resolve: (Conflict) -> Int): Ledger {
        if (!base.header.id.contentEquals(other.header.id)) {
            throw IllegalArgumentException("refusing to merge: different ledgers (file id mismatch)")
        }

        // Group every record from both copies by entity, preserving first-seen order.
        val groups = LinkedHashMap<Pair<String, String>, MutableList<Tagged>>()
        fun gather(entries: List<Entry>, fromOther: Boolean) {
            for (e in entries) {
                val key = classOf(e.env.k) to entityId(e.env)
                groups.getOrPut(key) { mutableListOf() }.add(Tagged(e.env, fromOther))
            }
        }
        gather(base.entries, false)
        gather(other.entries, true)

        val winners = ArrayList<Envelope>()
        for ((key, recs) in groups) {
            val sorted = recs.sortedBy { it.env.ts } // stable
            val maxTs = sorted.last().env.ts
            val distinct = distinctByPayload(sorted.filter { it.env.ts == maxTs })
            val winner = when (distinct.size) {
                1 -> distinct[0]
                else -> {
                    val idx = resolve(Conflict(key.first, key.second, maxTs, distinct.map { render(it) }))
                    require(idx in distinct.indices) { "merge: resolver returned out-of-range index $idx" }
                    distinct[idx]
                }
            }
            if (!isTombstone(winner.env.k)) winners.add(winner.env)
        }
        winners.sortBy { it.ts } // stable, preserves each record's own ts

        val newEntries = Writer.append(base.header, base.keys, emptyList(), winners)
        return Ledger(base.header, base.keys, newEntries, Replay.fold(newEntries))
    }

    private fun classOf(k: String): String = when (k) {
        "acct", "acct-del" -> "acct"
        "asset", "asset-del" -> "asset"
        "tx", "tx-edit", "tx-del" -> "tx"
        "label", "label-del" -> "label"
        "config" -> "config"
        else -> k
    }

    private fun isTombstone(k: String): Boolean =
        k == "acct-del" || k == "asset-del" || k == "tx-del" || k == "label-del"

    private fun entityId(env: Envelope): String {
        val field = if (classOf(env.k) == "config") "key" else "id"
        return env.d[field]?.jsonPrimitive?.content ?: error("merge: record of kind ${env.k} missing $field")
    }

    /** Collapses records with identical (kind, payload): identical concurrent writes aren't a conflict. */
    private fun distinctByPayload(top: List<Tagged>): List<Tagged> {
        val out = ArrayList<Tagged>()
        for (t in top) {
            if (out.none { it.env.k == t.env.k && it.env.d == t.env.d }) out.add(t)
        }
        return out
    }

    private fun render(t: Tagged): String {
        val src = if (t.fromOther) "(other)" else "(this file)"
        return "$src [${t.env.k}] ${t.env.d}"
    }
}
