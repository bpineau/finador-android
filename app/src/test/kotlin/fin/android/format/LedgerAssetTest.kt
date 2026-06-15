package fin.android.format

import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Money
import fin.android.domain.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Asset write/edit/delete through the .fin record log, plus the integrity guards. */
class LedgerAssetTest {
    private val pw = "pw"

    private fun asset(
        id: String,
        name: String,
        kind: AssetKind = AssetKind.SECURITY,
        ticker: String? = null,
        isin: String? = null,
        aliases: List<String> = emptyList(),
        group: String? = null,
        withholding: Double? = null,
    ) = Asset(id, kind, name, ticker, isin, aliases, "EUR", group, withholding)

    @Test
    fun addRoundTrips() {
        val l = Ledger.create(pw).putAsset(
            asset("a1", "Amundi MSCI World", ticker = "CW8.PA", isin = "LU1681043599",
                aliases = listOf("cw8"), group = "actions/monde", withholding = 0.15),
        )
        val a = Ledger.open(l.toBytes(), pw).book.assets.getValue("a1")
        assertEquals("Amundi MSCI World", a.name)
        assertEquals(AssetKind.SECURITY, a.kind)
        assertEquals("CW8.PA", a.ticker)
        assertEquals("LU1681043599", a.isin)
        assertEquals("EUR", a.ccy)
        assertEquals("actions/monde", a.group)
        assertEquals(0.15, a.withholding!!, 1e-9)
        assertEquals(listOf("cw8"), a.aliases)
    }

    @Test
    fun editOverwritesById() {
        val l = Ledger.create(pw)
            .putAsset(asset("a1", "MSCI World", ticker = "CW8.PA"))
            .putAsset(asset("a1", "World Tracker", kind = AssetKind.PROPERTY, group = "immo", aliases = listOf("wt")))
        val book = Ledger.open(l.toBytes(), pw).book
        assertEquals(1, book.assets.size)
        val a = book.assets.getValue("a1")
        assertEquals("World Tracker", a.name)
        assertEquals(AssetKind.PROPERTY, a.kind)
        assertEquals("immo", a.group)
        assertNull(a.ticker)
        assertEquals(listOf("wt"), a.aliases)
    }

    @Test
    fun deleteRemoves() {
        val l = Ledger.create(pw).putAsset(asset("a1", "MSCI World")).deleteAsset("a1")
        assertFalse(Ledger.open(l.toBytes(), pw).book.assets.containsKey("a1"))
    }

    @Test
    fun rejectsReferenceCollision() {
        // a2's name "cw8" collides case-insensitively with a1's alias "cw8".
        val l = Ledger.create(pw).putAsset(asset("a1", "MSCI World", aliases = listOf("cw8")))
        assertTrue(runCatching { l.putAsset(asset("a2", "CW8")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsTickerCollision() {
        val l = Ledger.create(pw).putAsset(asset("a1", "MSCI World", ticker = "CW8.PA"))
        assertTrue(runCatching { l.putAsset(asset("a2", "Other", ticker = "cw8.pa")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun editDoesNotSelfCollide() {
        // re-saving the same id with the same ticker/alias must be allowed (self is skipped).
        val l = Ledger.create(pw).putAsset(asset("a1", "MSCI World", ticker = "CW8.PA", aliases = listOf("cw8")))
        l.putAsset(asset("a1", "MSCI World", ticker = "CW8.PA", aliases = listOf("cw8", "monde"))) // no throw
    }

    @Test
    fun refusesDeletingReferencedAsset() {
        val l = Ledger.create(pw)
            .putAsset(asset("a1", "MSCI World"))
            .addTransaction(LocalDate.parse("2026-01-01"), "acc1", "a1", TxKind.buy, BigDecimal.ONE, Money(BigDecimal("100"), "EUR"))
        assertTrue(runCatching { l.deleteAsset("a1") }.exceptionOrNull() is IllegalArgumentException)
    }
}
