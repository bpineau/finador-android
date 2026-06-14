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
}
