package fin.android.market

import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ConverterTest {
    private val d = LocalDate.parse("2024-01-15")
    private fun ser(v: Double) = PriceSeries(listOf(PricePoint(d, v)))

    private val cv = Converter(mapOf("EUR" to ser(1.08), "GBP" to ser(1.25)))

    @Test fun sameCurrencyIsIdentity() {
        assertEquals(1.0, cv.rate("EUR", "EUR", d)!!, 0.0)
        assertEquals(1.0, cv.rate("USD", "USD", d)!!, 0.0)
    }

    @Test fun usdIsConstantOne() {
        assertEquals(1.08, cv.rate("EUR", "USD", d)!!, 1e-12)
        assertEquals(1.0 / 1.25, cv.rate("USD", "GBP", d)!!, 1e-12)
    }

    @Test fun crossViaUsd() {
        assertEquals(1.08 / 1.25, cv.rate("EUR", "GBP", d)!!, 1e-12)
    }

    @Test fun convertAppliesRate() {
        assertEquals(100.0 * (1.08 / 1.25), cv.convert(100.0, "EUR", "GBP", d)!!, 1e-9)
    }

    @Test fun missingRateIsNull() {
        assertNull(cv.rate("JPY", "USD", d))
        assertNull(cv.rate("EUR", "JPY", d))
        assertNull(cv.convert(100.0, "EUR", "JPY", d))
    }

    /**
     * A non-positive close is a missing point, not a rate. Unguarded, a zero on the QUOTE leg is
     * divided by, and every figure crossing that currency comes out Infinity - a number that then
     * travels silently into a total. Same rule as `Nowcast.liveRate`.
     */
    @Test fun aNonPositiveCloseIsNoRate() {
        val broken = Converter(mapOf("EUR" to ser(1.08), "GBP" to ser(0.0), "CHF" to ser(-1.1)))
        assertNull(broken.rate("EUR", "GBP", d)) // zero on the quote leg: was Infinity
        assertNull(broken.rate("GBP", "EUR", d)) // and on the base leg
        assertNull(broken.rate("EUR", "CHF", d))
        assertNull(broken.convert(100.0, "EUR", "GBP", d))
        assertEquals(1.08, broken.rate("EUR", "USD", d)!!, 1e-12) // the sound leg still crosses
    }
}
