package fin.android.format

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Opens a ledger freshly created by the Go `finador` CLI (path via -Dcrossimpl.go.file) and checks
 * Android reads it identically. Skipped unless the property is set (driven by scripts/crossimpl.sh).
 */
class CrossImplConsumerTest {
    @Test
    fun readsGoCreatedLedger() {
        val path = System.getProperty("crossimpl.go.file")
        assumeTrue("crossimpl.go.file not set", path != null)
        val pw = System.getProperty("crossimpl.go.pw") ?: "gopw"

        val ledger = Ledger.open(File(path!!).readBytes(), pw)
        assertEquals(1, ledger.book.accounts.size)
        val acc = ledger.book.accounts.values.first()
        assertEquals("Mon CTO", acc.name)
        assertEquals("gains:30%", acc.tax.toWire())
        assertEquals(1, ledger.book.txs.size)
        assertEquals(0, ledger.book.txs.values.first().amount.amount.compareTo(BigDecimal("1234")))
    }
}
