package fin.android.market

import fin.android.domain.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MultiSourceTest {
    private val from = LocalDate.parse("2024-01-01")
    private val ref = Ref(symbol = "ABC", isin = null)

    private class Fake(override val name: String, private val result: DailyData?) : Provider {
        var called = false
        override fun daily(ref: Ref, from: LocalDate): DailyData? { called = true; return result }
    }

    private fun nonEmpty(ccy: String) =
        DailyData(ccy, listOf(PricePoint(LocalDate.parse("2024-01-02"), 10.0)))

    @Test fun firstNullFallsThrough() {
        val a = Fake("a", null)
        val b = Fake("b", nonEmpty("EUR"))
        val out = MultiSource(listOf(a, b)).daily(ref, from)
        assertEquals("EUR", out!!.currency)
        assertEquals(true, a.called && b.called)
    }

    @Test fun firstEmptyFallsThrough() {
        val a = Fake("a", DailyData("USD", emptyList())) // non-null but empty closes
        val b = Fake("b", nonEmpty("GBP"))
        val out = MultiSource(listOf(a, b)).daily(ref, from)
        assertEquals("GBP", out!!.currency)
    }

    @Test fun firstNonEmptyWinsAndShortCircuits() {
        val a = Fake("a", nonEmpty("USD"))
        val b = Fake("b", nonEmpty("EUR"))
        val out = MultiSource(listOf(a, b)).daily(ref, from)
        assertEquals("USD", out!!.currency)
        assertEquals(false, b.called) // first usable provider short-circuits the chain
    }

    @Test fun allEmptyOrNullIsNull() {
        val a = Fake("a", null)
        val b = Fake("b", DailyData("USD", emptyList()))
        assertNull(MultiSource(listOf(a, b)).daily(ref, from))
    }

    @Test fun defaultBuildsYahooFtMorningstar() {
        // The default factory wires the standard chain; just assert it constructs.
        MultiSource.default()
    }
}
