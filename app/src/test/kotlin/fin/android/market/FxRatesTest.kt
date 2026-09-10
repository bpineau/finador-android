package fin.android.market

import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Rate SELECTION for display: which currencies get a line, against what, at which observed date.
 * The rates themselves are [Converter]'s (asserted by [ConverterTest]); nothing here recomputes one.
 */
class FxRatesTest {
    private val at = LocalDate.parse("2024-01-15")
    private val eurDay = LocalDate.parse("2024-01-12")
    private val gbpDay = LocalDate.parse("2024-01-11")

    private val fx = mapOf(
        "EUR" to PriceSeries(listOf(PricePoint(eurDay, 1.08))),
        "GBP" to PriceSeries(listOf(PricePoint(gbpDay, 1.25))),
    )

    @Test fun oneLinePerForeignCurrencySorted() {
        val rates = FxRates.held(listOf("USD", "GBP", "EUR"), "EUR", fx, at)
        assertEquals(listOf("GBP", "USD"), rates.map { it.base })
        assertTrue(rates.all { it.quote == "EUR" })
    }

    @Test fun rateIsTheConverterCross() {
        val rates = FxRates.held(listOf("USD"), "EUR", fx, at)
        assertEquals(1, rates.size)
        assertEquals(1.0 / 1.08, rates[0].rate, 1e-12)
    }

    @Test fun asOfIsTheLeastFreshLeg() {
        // GBP/EUR crosses two series: the pair is only as fresh as the older of the two.
        val gbp = FxRates.held(listOf("GBP"), "EUR", fx, at).single()
        assertEquals(gbpDay, gbp.asOf)
        // USD is the pivot and carries no date: only the EUR leg dates the cross.
        val usd = FxRates.held(listOf("USD"), "EUR", fx, at).single()
        assertEquals(eurDay, usd.asOf)
    }

    @Test fun referenceCurrencyIsNeverListed() {
        assertEquals(emptyList<FxRate>(), FxRates.held(listOf("EUR", "eur", " EUR "), "EUR", fx, at))
    }

    @Test fun singleCurrencyBookShowsNothing() {
        assertEquals(emptyList<FxRate>(), FxRates.held(emptyList(), "EUR", fx, at))
        assertEquals(emptyList<FxRate>(), FxRates.held(listOf("USD"), "USD", fx, at))
    }

    @Test fun missingRateIsDroppedNotZeroed() {
        val rates = FxRates.held(listOf("JPY", "GBP"), "EUR", fx, at)
        assertEquals(listOf("GBP"), rates.map { it.base })
    }

    @Test fun duplicatesAndCaseCollapseToOneLine() {
        val rates = FxRates.held(listOf("usd", "USD", "Usd"), "EUR", fx, at)
        assertEquals(1, rates.size)
        assertEquals("USD", rates[0].base)
    }

    @Test fun beforeAnyQuoteThereIsNoRate() {
        assertEquals(emptyList<FxRate>(), FxRates.held(listOf("USD"), "EUR", fx, LocalDate.parse("2020-01-01")))
    }
}
