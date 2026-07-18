package fin.android.remote

import fin.android.domain.Money
import fin.android.domain.TxKind
import fin.android.format.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** In-memory remote with knobs to drive offline, auth-failure and conflict paths. */
private class FakeBackend : Backend {
    var data: ByteArray? = null
    var version = 0
    var offline = false
    var auth = false // true = the token is rejected (401/403)
    var conflictNext = false

    override fun fetch(): Fetched {
        if (offline) throw RemoteError.Offline("offline")
        if (auth) throw RemoteError.Auth("token rejected (HTTP 401)")
        val d = data ?: throw RemoteError.Missing()
        return Fetched(d, version.toString())
    }

    override fun push(data: ByteArray, base: Version?, message: String): Version {
        if (offline) throw RemoteError.Offline("offline")
        if (auth) throw RemoteError.Auth("token rejected (HTTP 401)")
        if (conflictNext) { conflictNext = false; throw RemoteError.Conflict() }
        if (this.data != null && base != version.toString()) throw RemoteError.Conflict()
        this.data = data; version++; return version.toString()
    }

    override fun describe() = "fake"
}

class SyncTest {
    private val pw = "pw"
    private lateinit var wc: File
    private lateinit var st: File

    @Before fun setUp() {
        val dir = Files.createTempDirectory("synctest").toFile()
        wc = File(dir, "wc.fin")
        st = File(dir, "state.json")
    }

    private fun sync(be: Backend) = Sync(be, wc, st, Duration.ofHours(1), now = { Instant.parse("2026-06-14T12:00:00Z") })
    private fun emptyLedgerBytes() = Ledger.create(pw, t = 1, m = 8).toBytes()
    private fun addDeposit(l: Ledger) =
        l.addTransaction(LocalDate.parse("2026-01-01"), "acc", null, TxKind.deposit, BigDecimal.ZERO, Money(BigDecimal("100"), "EUR"))

    @Test
    fun openForReadPullsWhenAbsent() {
        val be = FakeBackend().apply { data = emptyLedgerBytes(); version = 7 }
        val ledger = sync(be).openForRead(pw)
        assertEquals(0, ledger.book.txs.size)
        assertTrue(wc.exists())
        assertEquals("7", sync(be).state().sha)
    }

    @Test
    fun mutatePushesWhenOnline() {
        val be = FakeBackend() // empty remote
        wc.writeBytes(emptyLedgerBytes()) // onboarding seeded the working copy
        val out = sync(be).mutate(pw, "add") { addDeposit(it) }
        assertTrue(out.pushed)
        assertFalse(out.dirty)
        assertNotNull(be.data)
        assertEquals(1, Ledger.open(be.data!!, pw).book.txs.size)
        assertFalse(sync(be).state().dirty)
    }

    @Test
    fun offlineMutateMarksDirtyAndKeepsLocal() {
        val be = FakeBackend().apply { offline = true }
        wc.writeBytes(emptyLedgerBytes())
        val out = sync(be).mutate(pw, "add") { addDeposit(it) }
        assertFalse(out.pushed)
        assertTrue(out.dirty)
        assertNull(be.data) // nothing reached the remote
        assertTrue(sync(be).state().dirty)
        assertEquals(1, Ledger.open(wc.readBytes(), pw).book.txs.size) // change persisted locally
    }

    // ---- auth failures: a rejected token must not lock the user out of local data ----

    @Test
    fun authWithLocalCopyReadsLocallyAndRecordsError() {
        val be = FakeBackend().apply { auth = true }
        wc.writeBytes(emptyLedgerBytes())
        val ledger = sync(be).openForRead(pw) // must NOT throw: local copy is readable
        assertEquals(0, ledger.book.txs.size)
        assertNotNull("authError recorded for the re-login banner", sync(be).state().authError)
    }

    @Test(expected = RemoteError.Auth::class)
    fun authWithoutLocalCopySurfaces() {
        val be = FakeBackend().apply { auth = true }
        sync(be).openForRead(pw) // nothing local to show: the error must surface
    }

    @Test
    fun authMutateKeepsLocalDirtyAndRecordsError() {
        val be = FakeBackend().apply { auth = true }
        wc.writeBytes(emptyLedgerBytes())
        val out = sync(be).mutate(pw, "add") { addDeposit(it) }
        assertFalse(out.pushed)
        assertTrue(out.dirty)
        assertNull(be.data) // nothing reached the remote
        val st = sync(be).state()
        assertTrue(st.dirty)
        assertNotNull(st.authError)
        assertEquals(1, Ledger.open(wc.readBytes(), pw).book.txs.size) // change persisted locally
    }

    @Test
    fun successfulSyncClearsAuthErrorAndPushesDirty() {
        val be = FakeBackend().apply { auth = true }
        wc.writeBytes(emptyLedgerBytes())
        sync(be).mutate(pw, "add") { addDeposit(it) } // rejected: dirty + authError
        assertNotNull(sync(be).state().authError)

        be.auth = false // the user re-logged in
        val out = sync(be).sync(pw)
        assertTrue(out.pushed)
        val st = sync(be).state()
        assertNull("healthy auth clears the banner", st.authError)
        assertFalse(st.dirty)
        assertEquals(1, Ledger.open(be.data!!, pw).book.txs.size) // the kept-local change reached the remote
    }

    @Test
    fun conflictTriggersMergeThenRepush() {
        val base = emptyLedgerBytes()
        val be = FakeBackend().apply { data = base; version = 1; conflictNext = true }
        wc.writeBytes(base)
        val out = sync(be).mutate(pw, "add") { addDeposit(it) }
        assertTrue(out.pushed)
        assertEquals(1, Ledger.open(be.data!!, pw).book.txs.size) // local change survived the merge
    }
}
