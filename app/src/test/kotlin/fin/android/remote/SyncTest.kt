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
    private fun addWithdraw(l: Ledger) =
        l.addTransaction(LocalDate.parse("2026-02-01"), "acc", null, TxKind.withdraw, BigDecimal.ZERO, Money(BigDecimal("40"), "EUR"))

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

    // ---- an unpushed (dirty) working copy must survive every fetch ----

    /** Offline write, then an online write: the fetch must not replace the unpushed record. */
    @Test
    fun mutateMergesOverAnUnpushedLocalChange() {
        val base = emptyLedgerBytes()
        val be = FakeBackend().apply { data = base; version = 1 }
        wc.writeBytes(base)

        be.offline = true
        assertTrue(sync(be).mutate(pw, "add 1") { addDeposit(it) }.dirty)

        be.offline = false
        val out = sync(be).mutate(pw, "add 2") { addWithdraw(it) }
        assertTrue(out.pushed)
        assertEquals("both records reached the remote", 2, Ledger.open(be.data!!, pw).book.txs.size)
        assertEquals(2, Ledger.open(wc.readBytes(), pw).book.txs.size)
    }

    /** A stale read after an offline write: the pull must not discard the unpushed record. */
    @Test
    fun pullKeepsAnUnpushedLocalChange() {
        val base = emptyLedgerBytes()
        val be = FakeBackend().apply { data = base; version = 1 }
        wc.writeBytes(base)

        be.offline = true
        assertTrue(sync(be).mutate(pw, "add") { addDeposit(it) }.dirty)

        be.offline = false
        assertEquals(1, sync(be).openForRead(pw).book.txs.size)
        assertTrue("still unpushed", sync(be).state().dirty)
    }

    // ---- a write that did not finish (process death, the Android normal case) ----

    /**
     * Every path in [Sync] starts by reading the state file, and an unreadable one used to throw
     * out of [Sync.state] - so one short write (the file is rewritten on every pull, mutate and
     * sync) made the UNLOCK itself fail, permanently, since nothing rewrites the file until a sync
     * runs. It must degrade instead, and degrade to DIRTY: the flag a corrupt file lost is the one
     * that stops a pull from overwriting unpushed records.
     */
    @Test
    fun aTruncatedStateFileIsReadAsDirtyRatherThanThrowing() {
        st.writeText("""{"sha":"3","dirty":tr""") // a write killed halfway
        val be = FakeBackend().apply { data = emptyLedgerBytes(); version = 9 }

        assertTrue(sync(be).state().dirty)

        // And the conservative reading is load-bearing: the local copy is not pulled over.
        val local = addDeposit(Ledger.open(emptyLedgerBytes(), pw)).toBytes()
        wc.writeBytes(local)
        sync(be).pullIfStale()
        assertEquals("the unpushed record must survive", 1, Ledger.open(wc.readBytes(), pw).book.txs.size)
    }

    /**
     * A working copy left short by an older build (or by any write that is not atomic) cannot be
     * opened, and the dirty guard forbids healing it by pulling: the app would be stuck on the
     * unlock screen for good. The backup every write keeps is the way out, and what it recovers is
     * marked dirty so the next sync MERGES it with the remote instead of either side winning.
     */
    @Test
    fun aTruncatedWorkingCopyIsRecoveredFromItsBackup() {
        val base = emptyLedgerBytes()
        val be = FakeBackend().apply { data = base; version = 1 }
        wc.writeBytes(base)

        be.offline = true
        assertTrue(sync(be).mutate(pw, "add") { addDeposit(it) }.dirty) // wc = 1 record, .bak = none
        assertTrue(sync(be).mutate(pw, "add") { addWithdraw(it) }.dirty) // wc = 2, .bak = 1
        val good = wc.readBytes()
        wc.writeBytes(good.copyOfRange(0, good.size / 2)) // ... and that second write dies halfway

        // The backup is one write behind by construction, so the interrupted edit is the only
        // casualty: everything written before it comes back.
        val ledger = sync(be).openForRead(pw)
        assertEquals(1, ledger.book.txs.size)
        assertTrue("the recovered copy must be reconciled, not assumed pushed", sync(be).state().dirty)
        // Healed on disk too: the next open needs no recovery.
        assertEquals(1, sync(be).openForRead(pw).book.txs.size)
    }

    /** A wrong passphrase is not a corruption: it must surface as itself, not as a recovery. */
    @Test
    fun aWrongPassphraseIsNotRecoveredFromTheBackup() {
        val be = FakeBackend().apply { data = emptyLedgerBytes(); version = 1 }
        sync(be).openForRead(pw)
        sync(be).mutate(pw, "add") { addDeposit(it) } // now there is a .bak

        var failed = false
        try {
            sync(be).openForRead("not the passphrase")
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse("nothing was 'recovered', so nothing became dirty", sync(be).state().dirty)
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
