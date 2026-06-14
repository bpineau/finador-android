package fin.android.format

import fin.android.domain.Money
import fin.android.domain.TxKind
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MergeTest {
    private val pw = "finador-format-spec-v3"
    private val peaId = "06fc2cjx2bvtjjxmtmcj2wg"
    private val cw8Id = "06fc2cjxndn8wez8qqhh0a0"
    private val buyId = "06fc2cjy7j4ncgb2p6mg0a0"

    private fun base(): Ledger =
        Ledger.open(javaClass.getResourceAsStream("/sample.ledger")!!.use { it.readBytes() }, pw)

    private fun Ledger.editEnv(ts: String, amount: String): Ledger {
        val dto = TxDto(buyId, "2024-01-20", peaId, cw8Id, "buy", "20", MoneyDto(amount, "EUR"), null, null)
        return append(listOf(Envelope("tx-edit", ts, wireJson.encodeToJsonElement(TxDto.serializer(), dto).jsonObject)))
    }

    @Test
    fun unionsDistinctConcurrentAdds() {
        val base = base()
        val a = base.addTransaction(LocalDate.parse("2026-06-10"), peaId, cw8Id, TxKind.buy, BigDecimal("5"), Money(BigDecimal("2500"), "EUR"))
        val b = base.addTransaction(LocalDate.parse("2026-06-11"), peaId, cw8Id, TxKind.sell, BigDecimal("3"), Money(BigDecimal("1600"), "EUR"))
        val merged = a.merge(b)
        assertEquals(5, merged.book.txs.size) // 3 original + 2 distinct adds
        assertEquals(5, Ledger.open(merged.toBytes(), pw).book.txs.size)
    }

    @Test
    fun lastWriterWinsByTs() {
        val base = base()
        val older = base.editEnv("2000-01-01T00:00:00Z", "100")
        val newer = base.editEnv("2099-01-01T00:00:00Z", "999")
        assertEquals(0, older.merge(newer).book.txs.getValue(buyId).amount.amount.compareTo(BigDecimal("999")))
        assertEquals(0, newer.merge(older).book.txs.getValue(buyId).amount.amount.compareTo(BigDecimal("999")))
    }

    @Test
    fun identicalConcurrentWriteIsNotAConflict() {
        val base = base()
        val a = base.editEnv("2099-01-01T00:00:00Z", "9000")
        val b = base.editEnv("2099-01-01T00:00:00Z", "9000")
        val merged = a.merge(b) { error("resolver must not be called for identical payloads") }
        assertEquals(0, merged.book.txs.getValue(buyId).amount.amount.compareTo(BigDecimal("9000")))
    }

    @Test
    fun laterTombstoneKeepsEntityDeleted() {
        val base = base()
        val del = base.append(
            listOf(Envelope("tx-del", "2099-01-01T00:00:00Z", wireJson.encodeToJsonElement(IdRefDto.serializer(), IdRefDto(buyId)).jsonObject)),
        )
        val edit = base.editEnv("2000-01-01T00:00:00Z", "100")
        val merged = del.merge(edit)
        assertFalse(merged.book.txs.containsKey(buyId))
        assertEquals(2, merged.book.txs.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesDifferentLedgers() {
        Ledger.create("pw").merge(Ledger.create("pw"))
    }
}
