package fin.android.market

import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class CacheSidecarTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keyCache = ByteArray(32) { it.toByte() } // fixed 32-byte cache key
    private fun d(s: String) = LocalDate.parse(s)

    private val data = MarketData(
        prices = mapOf(
            "asset-1" to PriceSeries(
                listOf(PricePoint(d("2024-01-20"), 450.0), PricePoint(d("2024-01-22"), 455.5)),
                fetchedAt = d("2024-01-23"),
            ),
        ),
        fx = mapOf(
            "EUR" to PriceSeries(listOf(PricePoint(d("2024-01-20"), 1.08)), fetchedAt = d("2024-01-21")),
        ),
        dividends = mapOf(
            "asset-1" to listOf(DividendEvent(d("2024-03-10"), 1.25)),
        ),
    )

    @Test fun roundTrip() {
        val f = tmp.newFile("ledger.cache")
        CacheSidecar.write(f, keyCache, data)
        val back = CacheSidecar.read(f, keyCache)
        assertEquals(data, back)
    }

    @Test fun onDiskBytesStartWithMagic() {
        val f = tmp.newFile("magic.cache")
        CacheSidecar.write(f, keyCache, data)
        val raw = f.readBytes()
        assertEquals("FINCACHE2", String(raw.copyOfRange(0, 9), Charsets.US_ASCII))
    }

    @Test fun garbageReturnsNull() {
        val f = tmp.newFile("garbage.cache")
        f.writeBytes("not a fincache file at all".toByteArray())
        assertNull(CacheSidecar.read(f, keyCache))
    }

    @Test fun wrongMagicReturnsNull() {
        val f = tmp.newFile("wrongmagic.cache")
        val good = run {
            val g = tmp.newFile("good.cache"); CacheSidecar.write(g, keyCache, data); g.readBytes()
        }
        val tampered = good.copyOf()
        tampered[0] = 'X'.code.toByte() // corrupt the magic
        f.writeBytes(tampered)
        assertNull(CacheSidecar.read(f, keyCache))
    }

    @Test fun missingFileReturnsNull() {
        assertNull(CacheSidecar.read(File(tmp.root, "does-not-exist.cache"), keyCache))
    }

    @Test fun wrongKeyReturnsNull() {
        val f = tmp.newFile("wrongkey.cache")
        CacheSidecar.write(f, keyCache, data)
        assertNull(CacheSidecar.read(f, ByteArray(32) { (it + 1).toByte() }))
    }

    @Test fun cacheFileNameIsUrlSafeNoPad() {
        val id = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x00, 0x10)
        assertEquals("-_8AEA.cache", CacheSidecar.cacheFileName(id))
    }
}
