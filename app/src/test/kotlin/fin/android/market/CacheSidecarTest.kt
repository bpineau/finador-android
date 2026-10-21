package fin.android.market

import fin.android.crypto.AesGcm
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

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

    // An estimate is not a fact: the sidecar is the one door that keeps nowcast tails off disk,
    // and the on-disk JSON stays byte-compatible with the Go implementation, which has no field
    // for one.
    @Test fun estimatesAreNeverWritten() {
        val estimated = MarketData(
            prices = mapOf(
                "fcpe" to PriceSeries(
                    listOf(
                        PricePoint(d("2026-08-18"), 51.0),
                        PricePoint(d("2026-08-19"), 52.0), // estimated
                        PricePoint(d("2026-08-20"), 53.0), // estimated
                    ),
                    fetchedAt = d("2026-08-20"),
                    estimatedFrom = d("2026-08-19"),
                    estimateProxy = "URTH",
                ),
            ),
        )
        val f = tmp.newFile("estimated.cache")
        CacheSidecar.write(f, keyCache, estimated)
        val back = CacheSidecar.read(f, keyCache)!!
        val series = back.prices["fcpe"]!!
        assertEquals(listOf(d("2026-08-18")), series.points.map { it.date })
        assertNull(series.estimatedFrom)
        assertNull(series.estimateProxy)
        assertEquals(d("2026-08-20"), series.fetchedAt) // the refresh day is still a fact
    }

    @Test fun onDiskBytesStartWithMagic() {
        val f = tmp.newFile("magic.cache")
        CacheSidecar.write(f, keyCache, data)
        val raw = f.readBytes()
        assertEquals("FINCACHE3", String(raw.copyOfRange(0, 9), Charsets.US_ASCII))
    }

    /**
     * The magic is the cache's version (FORMAT.md §7, Go D55): a well-formed FINCACHE2 sidecar holds
     * currency crosses dated on the UTC calendar, a session off, which incremental refreshes never
     * rewrite. It is ignored like a missing file, and the next refresh rebuilds every series.
     */
    @Test fun aFincache2SidecarIsIgnored() {
        val f = tmp.newFile("v2.cache")
        val magic = "FINCACHE2".toByteArray(Charsets.US_ASCII)
        val jsonBytes = """{"fx":{"EUR":{"points":[{"d":"2024-01-20","c":1.08}]}}}""".toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(jsonBytes) }
        val nonce = ByteArray(AesGcm.NONCE_LEN) { 7 }
        f.writeBytes(magic + nonce + AesGcm.seal(keyCache, nonce, gz.toByteArray(), magic))
        assertNull(CacheSidecar.read(f, keyCache))
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
