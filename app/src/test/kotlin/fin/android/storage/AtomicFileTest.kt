package fin.android.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFileTest {
    private lateinit var dir: File
    private lateinit var f: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("atomic").toFile()
        f = File(dir, "ledger.fin")
    }

    @Test fun writeReplacesTheContentsAndLeavesNoTemporary() {
        AtomicFile.write(f, "one".toByteArray())
        AtomicFile.write(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertEquals(listOf("ledger.fin"), dir.list()!!.sorted())
    }

    @Test fun writeCreatesTheParentDirectory() {
        val nested = File(File(dir, "a/b"), "c.fin")
        AtomicFile.write(nested, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), nested.readBytes())
    }

    /** The backup is the contents being REPLACED, which is what a corrupted target falls back to. */
    @Test fun theBackupHoldsThePreviousContents() {
        AtomicFile.write(f, "first".toByteArray(), backup = true)
        assertFalse("nothing to back up on a first write", AtomicFile.backupOf(f).exists())
        AtomicFile.write(f, "second".toByteArray(), backup = true)
        assertEquals("second", f.readText())
        assertEquals("first", AtomicFile.backupOf(f).readText())
    }

    /** A temporary left by a write that died is overwritten, never appended to. */
    @Test fun aLeftoverTemporaryIsNotReused() {
        File(dir, "ledger.fin.tmp").writeText("garbage from a killed write")
        AtomicFile.write(f, "clean".toByteArray())
        assertEquals("clean", f.readText())
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }
}
