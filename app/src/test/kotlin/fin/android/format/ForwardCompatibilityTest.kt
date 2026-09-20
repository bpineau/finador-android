package fin.android.format

import fin.android.domain.Money
import fin.android.domain.TxKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * What this app must do with a ledger a NEWER finador wrote (FORMAT.md §8).
 *
 * This is the classic companion-app data-loss bug: the mobile client reads a file it only half
 * understands, writes it back, and the fields it did not recognize are gone - silently, for every
 * device, since the rewrite is what the others then pull. The format's answer is rule 2 (unknown
 * FIELDS of a known kind are tolerated and round-tripped) and rule 3 (an unknown KIND is a hard
 * error: a financial log must never skip a record it cannot read, because a record can hide money).
 */
class ForwardCompatibilityTest {
    private val pw = "pw"

    private fun ledger() = Ledger.create(pw, t = 1, m = 8)

    /** A `tx` carrying a field this build knows nothing about, as a newer writer would emit it. */
    private fun futureTx(id: String) = Envelope(
        k = "tx",
        ts = Rfc3339.now(),
        d = buildJsonObject {
            put("id", id)
            put("date", "2026-01-05")
            put("account", "acct-zephyr")
            put("kind", "deposit")
            put("qty", "0")
            put("amount", buildJsonObject { put("amount", "100"); put("ccy", "EUR") })
            put("settlement", "2026-01-07") // the field of a newer finador
        },
    )

    private fun lineOf(l: Ledger, id: String): String =
        l.entries.first { it.env.d["id"].toString().contains(id) }.line

    /** Rule 2, read side: the record folds into the book, the extra field simply ignored. */
    @Test fun anUnknownFieldDoesNotStopTheLedgerFromOpening() {
        val bytes = ledger().append(listOf(futureTx("tx-future"))).toBytes()
        val book = Ledger.open(bytes, pw).book
        assertEquals(1, book.txs.size)
        assertEquals(TxKind.deposit, book.txs["tx-future"]!!.kind)
    }

    /**
     * Rule 2, write side, and the whole point of diff-on-save: writing a NEW record must re-emit
     * every existing one byte for byte, so a field this build cannot name survives a round trip
     * through the phone.
     */
    @Test fun anUnknownFieldSurvivesAWriteFromThisApp() {
        val original = ledger().append(listOf(futureTx("tx-future")))
        val after = original.addTransaction(
            LocalDate.parse("2026-02-01"), "acct-zephyr", null, TxKind.withdraw,
            BigDecimal.ZERO, Money(BigDecimal("40"), "EUR"),
        )
        val reopened = Ledger.open(after.toBytes(), pw)

        assertEquals(2, reopened.book.txs.size)
        assertEquals(lineOf(original, "tx-future"), lineOf(reopened, "tx-future"))
        assertTrue(
            "the newer writer's field must still be in the record",
            reopened.entries.first { it.env.k == "tx" }.env.d.containsKey("settlement"),
        )
    }

    /**
     * Rule 2 through the MERGE, which re-seals every record rather than re-emitting its line: the
     * payload it re-seals is the one it parsed, unknown keys included. A merge is what two devices
     * run on each other's writes, so a loss here would spread to the whole fleet.
     */
    @Test fun anUnknownFieldSurvivesAMerge() {
        val base = ledger()
        val mine = base.append(listOf(futureTx("tx-future")))
        val theirs = base.addTransaction(
            LocalDate.parse("2026-03-01"), "acct-zephyr", null, TxKind.deposit,
            BigDecimal.ZERO, Money(BigDecimal("10"), "EUR"),
        )
        val merged = Ledger.open(mine.merge(theirs).toBytes(), pw)

        assertEquals(2, merged.book.txs.size)
        val future = merged.entries.first { it.env.d["id"].toString().contains("tx-future") }
        assertEquals("\"2026-01-07\"", future.env.d["settlement"].toString())
    }

    /**
     * Rule 3: an unknown KIND is refused outright, and refused as ITS OWN error, so the UI can say
     * "this file was written by a newer finador" instead of "wrong passphrase or corrupt file".
     */
    @Test fun anUnknownRecordKindIsARefusalNotASkip() {
        // Written through the writer directly: this build's own Ledger.append folds as it goes and
        // would refuse to BUILD such a record, which is the same rule seen from the other side.
        val l = ledger()
        val unknown = Envelope("budget", Rfc3339.now(), buildJsonObject { put("id", "b1") })
        val bytes = Writer.serialize(l.header, l.keys, Writer.append(l.header, l.keys, l.entries, listOf(unknown)))
        try {
            Ledger.open(bytes, pw)
            throw AssertionError("an unknown kind must not be skipped: a record can hide money")
        } catch (e: UnsupportedFormatException) {
            assertTrue(e.message!!.contains("budget"))
        }
    }
}
