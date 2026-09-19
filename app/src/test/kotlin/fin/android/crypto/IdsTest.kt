package fin.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class IdsTest {
    private val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"

    @Test
    fun lengthAndAlphabet() {
        val id = Ids.newId(1_700_000_000_000L, ByteArray(8) { it.toByte() })
        assertEquals(23, id.length)
        assertTrue(id.all { it in alphabet })
    }

    @Test
    fun lexicographicallyTimeSortable() {
        val earlier = Ids.newId(1_000_000_000_000L, ByteArray(8))
        val later = Ids.newId(2_000_000_000_000L, ByteArray(8))
        assertTrue(earlier < later)
    }

    @Test
    fun randomIdsAreDistinct() {
        val a = Ids.newId()
        val b = Ids.newId()
        assertEquals(23, a.length)
        assertTrue(a != b)
    }

    /**
     * The property an import depends on: a tight loop mints far more ids than the millisecond
     * clock can separate, and they must still come out strictly increasing - and unique.
     */
    @Test
    fun monotonicInATightLoop() {
        val n = 10000
        val seen = HashSet<String>(n * 2)
        var previous: String? = null
        repeat(n) {
            val id = Ids.newId()
            assertEquals(23, id.length)
            assertTrue("duplicate id $id", seen.add(id))
            val before = previous
            if (before != null) assertTrue("$id does not sort after $before", id > before)
            previous = id
        }
    }

    /** Thread safety: ids stay unique and well-formed under parallel minting. */
    @Test
    fun concurrentMintingStaysUnique() {
        val threads = 16
        val each = 500
        val out = ConcurrentLinkedQueue<String>()
        val workers = (1..threads).map { Thread { repeat(each) { out.add(Ids.newId()) } } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads * each, out.size)
        assertEquals(threads * each, out.toHashSet().size)
    }

    /**
     * A clock stepping backwards (a time correction, a resumed device) must not break
     * ordering: the generator keeps the last millisecond it used and walks the tail instead.
     */
    @Test
    fun backwardsClockKeepsOrdering() {
        val ahead = Ids.mint(System.currentTimeMillis() + 5L)
        val back = Ids.mint(1_000_000_000_000L) // far in the past
        assertTrue("$back does not sort after $ahead", back > ahead)
        assertEquals(23, back.length)
    }

    /** Same millisecond, many ids: the tail walks and the timestamp prefix stays put. */
    @Test
    fun sameMillisecondWalksTheTail() {
        val ms = System.currentTimeMillis()
        val ids = (1..1000).map { Ids.mint(ms) }
        for (i in 1 until ids.size) assertTrue("${ids[i]} !> ${ids[i - 1]}", ids[i] > ids[i - 1])
        // The 48-bit prefix spans 9.6 characters, so the first 9 belong to the millisecond
        // alone - and a run of ids minted inside one tick shares them.
        assertTrue(ids.all { it.take(9) == ids.first().take(9) })
    }
}
