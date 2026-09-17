package fin.android.market

import fin.android.domain.PriceSeries
import java.time.LocalDate

/**
 * Converts amounts between currencies by crossing through the USD, using the cached FX series
 * ([fx], the value of one unit in USD). USD itself is the constant 1.
 */
class Converter(val fx: Map<String, PriceSeries>) {

    /**
     * How many USD one unit of [c] is worth at [d]; null when the rate is missing.
     *
     * A non-positive close is MISSING, not a rate: an exchange rate is strictly positive, and a
     * zero served on the quote leg is divided by in [rate], handing every figure that crosses [c]
     * an Infinity no caller tests for. Same guard as `Nowcast.liveRate`.
     */
    private fun usdValue(c: String, d: LocalDate): Double? {
        if (c == USD) return 1.0
        return fx[c]?.at(d)?.first?.takeIf { it > 0 }
    }

    /** The multiplier turning an amount in [from] into [to] at date [d]; null when a rate is missing. */
    fun rate(from: String, to: String, d: LocalDate): Double? {
        if (from == to) return 1.0
        val f = usdValue(from, d) ?: return null
        val t = usdValue(to, d) ?: return null
        return f / t
    }

    fun convert(amount: Double, from: String, to: String, d: LocalDate): Double? {
        val r = rate(from, to, d) ?: return null
        return amount * r
    }

    companion object {
        const val USD = "USD"
    }
}
