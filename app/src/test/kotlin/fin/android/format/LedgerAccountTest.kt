package fin.android.format

import fin.android.domain.Account
import fin.android.domain.Money
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Account write/edit/delete through the .fin record log, plus the integrity guards. */
class LedgerAccountTest {
    private val pw = "pw"

    private fun acct(id: String, name: String, tax: TaxRule = TaxRule.None, aliases: List<String> = emptyList()) =
        Account(id, name, "EUR", tax, aliases)

    @Test
    fun addRoundTrips() {
        val l = Ledger.create(pw).putAccount(acct("a1", "PEA BforBank", TaxRule.Gains(BigDecimal("0.172")), listOf("pea")))
        val a = Ledger.open(l.toBytes(), pw).book.accounts.getValue("a1")
        assertEquals("PEA BforBank", a.name)
        assertEquals("EUR", a.ccy)
        assertEquals("gains:17.2%", a.tax.toWire())
        assertEquals(listOf("pea"), a.aliases)
    }

    @Test
    fun editOverwritesById() {
        val l = Ledger.create(pw)
            .putAccount(acct("a1", "PEA", TaxRule.Gains(BigDecimal("0.172"))))
            .putAccount(acct("a1", "PEA renamed", TaxRule.Value(BigDecimal("0.10")), listOf("p")))
        val book = Ledger.open(l.toBytes(), pw).book
        assertEquals(1, book.accounts.size)
        val a = book.accounts.getValue("a1")
        assertEquals("PEA renamed", a.name)
        assertEquals("value:10%", a.tax.toWire())
        assertEquals(listOf("p"), a.aliases)
    }

    @Test
    fun deleteRemoves() {
        val l = Ledger.create(pw).putAccount(acct("a1", "PEA")).deleteAccount("a1")
        assertFalse(Ledger.open(l.toBytes(), pw).book.accounts.containsKey("a1"))
    }

    @Test
    fun rejectsReferenceCollision() {
        // a2's name "pea" collides case-insensitively with a1's alias "pea".
        val l = Ledger.create(pw).putAccount(acct("a1", "PEA BforBank", aliases = listOf("pea")))
        assertTrue(runCatching { l.putAccount(acct("a2", "pea")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun editDoesNotSelfCollide() {
        // re-saving the same id with the same name/alias must be allowed (self is skipped).
        val l = Ledger.create(pw).putAccount(acct("a1", "PEA", aliases = listOf("pea")))
        l.putAccount(acct("a1", "PEA", aliases = listOf("pea", "bourso"))) // no throw
    }

    @Test
    fun refusesDeletingReferencedAccount() {
        val l = Ledger.create(pw)
            .putAccount(acct("a1", "PEA"))
            .addTransaction(LocalDate.parse("2026-01-01"), "a1", null, TxKind.deposit, BigDecimal.ZERO, Money(BigDecimal("100"), "EUR"))
        assertTrue(runCatching { l.deleteAccount("a1") }.exceptionOrNull() is IllegalArgumentException)
    }
}
