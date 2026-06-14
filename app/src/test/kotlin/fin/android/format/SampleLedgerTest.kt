package fin.android.format

import fin.android.domain.AssetKind
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * End-to-end golden test (FORMAT.md §9.2): decrypts the committed sample ledger (header → 7
 * records → trailer) and checks the folded contents against the documented expectations.
 */
class SampleLedgerTest {
    private fun sampleBytes(): ByteArray =
        javaClass.getResourceAsStream("/sample.ledger")!!.use { it.readBytes() }

    @Test
    fun decryptsAndFolds() {
        val ledger = Ledger.open(sampleBytes(), "finador-format-spec-v3")
        val book = ledger.book

        assertEquals(2, book.accounts.size)
        val pea = book.accounts.getValue("06fc2cjx2bvtjjxmtmcj2wg")
        assertEquals("PEA BforBank", pea.name)
        assertEquals("EUR", pea.ccy)
        assertEquals("gains:17.2%", pea.tax.toWire())
        assertEquals(listOf("pea"), pea.aliases)
        assertEquals(TaxRule.None, book.accounts.getValue("06fc2cjx8mhna9ngpakpvm0").tax)

        assertEquals(2, book.assets.size)
        val cw8 = book.assets.getValue("06fc2cjxndn8wez8qqhh0a0")
        assertEquals(AssetKind.SECURITY, cw8.kind)
        assertEquals("CW8.PA", cw8.ticker)
        assertEquals("equities/world", cw8.group)
        assertEquals(listOf("cw8"), cw8.aliases)
        val appart = book.assets.getValue("06fc2cjxf7702jynqpzgga8")
        assertEquals(AssetKind.PROPERTY, appart.kind)
        assertEquals("realestate", appart.group)

        assertEquals(3, book.txs.size)
        val buy = book.txs.getValue("06fc2cjy7j4ncgb2p6mg0a0")
        assertEquals(TxKind.buy, buy.kind)
        assertEquals(LocalDate.parse("2024-01-20"), buy.date)
        assertEquals("06fc2cjx2bvtjjxmtmcj2wg", buy.account)
        assertEquals("06fc2cjxndn8wez8qqhh0a0", buy.asset)
        assertEquals(0, buy.qty.compareTo(BigDecimal("20")))
        assertEquals(0, buy.amount.amount.compareTo(BigDecimal("9000")))
        assertEquals("EUR", buy.amount.ccy)

        val statement = book.txs.getValue("06fc2cjye7137cmsvzjh4br")
        assertEquals(TxKind.statement, statement.kind)
        assertEquals(0, statement.amount.amount.compareTo(BigDecimal("15000")))
    }

    @Test(expected = BadPasswordOrCorruptException::class)
    fun wrongPasswordFails() {
        Ledger.open(sampleBytes(), "not-the-passphrase")
    }
}
