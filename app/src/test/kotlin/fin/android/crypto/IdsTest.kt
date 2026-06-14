package fin.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
