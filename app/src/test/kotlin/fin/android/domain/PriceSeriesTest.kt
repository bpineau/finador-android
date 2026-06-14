package fin.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PriceSeriesTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val series = PriceSeries(
        listOf(
            PricePoint(d("2024-01-10"), 100.0),
            PricePoint(d("2024-01-15"), 110.0),
            PricePoint(d("2024-01-20"), 120.0),
        ),
    )

    @Test fun atExactHit() {
        val (close, date) = series.at(d("2024-01-15"))!!
        assertEquals(110.0, close, 0.0)
        assertEquals(d("2024-01-15"), date)
    }

    @Test fun atBeforeFirstIsNull() {
        assertNull(series.at(d("2024-01-01")))
    }

    @Test fun atBetweenForwardFillsPrior() {
        val (close, date) = series.at(d("2024-01-17"))!!
        assertEquals(110.0, close, 0.0) // last close at or before the 17th is the 15th
        assertEquals(d("2024-01-15"), date)
    }

    @Test fun atAfterLastReturnsLast() {
        val (close, date) = series.at(d("2024-02-01"))!!
        assertEquals(120.0, close, 0.0)
        assertEquals(d("2024-01-20"), date)
    }

    @Test fun atEmptyIsNull() {
        assertNull(PriceSeries().at(d("2024-01-01")))
    }

    @Test fun mergeUpsertsSortsAndDedupes() {
        val merged = series.merge(
            listOf(
                PricePoint(d("2024-01-15"), 999.0), // upsert existing
                PricePoint(d("2024-01-05"), 90.0),  // insert before
                PricePoint(d("2024-01-25"), 130.0), // append
            ),
        )
        assertEquals(
            listOf(d("2024-01-05"), d("2024-01-10"), d("2024-01-15"), d("2024-01-20"), d("2024-01-25")),
            merged.points.map { it.date },
        )
        assertEquals(999.0, merged.at(d("2024-01-15"))!!.first, 0.0)
        // original series is unchanged (immutability)
        assertEquals(110.0, series.at(d("2024-01-15"))!!.first, 0.0)
    }

    @Test fun lastReturnsLatestPoint() {
        assertEquals(PricePoint(d("2024-01-20"), 120.0), series.last())
        assertNull(PriceSeries().last())
    }
}
