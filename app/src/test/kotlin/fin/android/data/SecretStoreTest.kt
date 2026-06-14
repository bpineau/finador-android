package fin.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    @Test
    fun storesAndPurges() {
        val s = InMemorySecretStore()
        assertNull(s.getPat())
        assertFalse(s.hasPassphrase())

        s.putPat("tok")
        s.putPassphrase("pw")
        assertEquals("tok", s.getPat())
        assertEquals("pw", s.getPassphrase())
        assertTrue(s.hasPassphrase())

        s.purge()
        assertNull(s.getPat())
        assertNull(s.getPassphrase())
        assertFalse(s.hasPassphrase())
    }
}
