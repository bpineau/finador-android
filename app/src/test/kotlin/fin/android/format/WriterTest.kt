package fin.android.format

import fin.android.domain.Money
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class WriterTest {
    private val pw = "finador-format-spec-v3"
    private val peaId = "06fc2cjx2bvtjjxmtmcj2wg"
    private val cw8Id = "06fc2cjxndn8wez8qqhh0a0"
    private val buyId = "06fc2cjy7j4ncgb2p6mg0a0"

    private fun sample(): ByteArray =
        javaClass.getResourceAsStream("/sample.ledger")!!.use { it.readBytes() }

    @Test
    fun addRoundTripsAndKeepsPrefixByteStable() {
        val orig = Ledger.open(sample(), pw)
        val mutated = orig.addTransaction(
            LocalDate.parse("2026-06-10"), peaId, cw8Id, TxKind.buy, BigDecimal("5"), Money(BigDecimal("2500"), "EUR"),
        )
        val reopened = Ledger.open(mutated.toBytes(), pw)

        assertEquals(4, reopened.book.txs.size)
        // the 7 pre-existing record lines must be re-emitted byte-for-byte
        for (i in orig.entries.indices) assertEquals(orig.entries[i].line, reopened.entries[i].line)

        val added = reopened.book.txs.values.first { it.qty.compareTo(BigDecimal("5")) == 0 }
        assertEquals(TxKind.buy, added.kind)
        assertEquals(peaId, added.account)
        assertEquals(cw8Id, added.asset)
        assertEquals(0, added.amount.amount.compareTo(BigDecimal("2500")))
    }

    @Test
    fun deleteRemovesTx() {
        val reopened = Ledger.open(Ledger.open(sample(), pw).deleteTransaction(buyId).toBytes(), pw)
        assertEquals(2, reopened.book.txs.size)
        assertFalse(reopened.book.txs.containsKey(buyId))
    }

    @Test
    fun editChangesTx() {
        val edited = Ledger.open(sample(), pw).editTransaction(
            buyId, LocalDate.parse("2024-01-20"), peaId, cw8Id, TxKind.buy, BigDecimal("20"), Money(BigDecimal("9500"), "EUR"),
        )
        val reopened = Ledger.open(edited.toBytes(), pw)
        assertEquals(3, reopened.book.txs.size)
        assertEquals(0, reopened.book.txs.getValue(buyId).amount.amount.compareTo(BigDecimal("9500")))
    }

    @Test
    fun createEmptyRoundTrips() {
        val reopened = Ledger.open(Ledger.create("pw").toBytes(), "pw")
        assertEquals(0, reopened.book.txs.size)
        assertEquals(0, reopened.book.accounts.size)
    }

    @Test
    fun createThenAddRoundTrips() {
        val l = Ledger.create("pw").addTransaction(
            LocalDate.parse("2026-01-01"), "acc1", null, TxKind.deposit, BigDecimal.ZERO, Money(BigDecimal("100"), "EUR"),
        )
        assertEquals(1, Ledger.open(l.toBytes(), "pw").book.txs.size)
    }

    @Test(expected = BadPasswordOrCorruptException::class)
    fun createdLedgerRejectsWrongPassword() {
        Ledger.open(Ledger.create("pw").toBytes(), "wrong")
    }
}
