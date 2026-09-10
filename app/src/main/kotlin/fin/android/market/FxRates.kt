package fin.android.market

import fin.android.domain.PriceSeries
import java.time.LocalDate

/**
 * One exchange rate as the UI states it: "1 [base] = [rate] [quote]". [asOf] is the day the rate was
 * actually observed (the least fresh of the two legs the cross went through), null when no leg
 * carries a date (both sides are the USD pivot itself).
 */
data class FxRate(val base: String, val quote: String, val rate: Double, val asOf: LocalDate?)

/**
 * The exchange rates a valuation leaned on, for display only. Nothing here computes a new number:
 * every rate is read back from the same cached FX series [Converter] crossed through when the
 * positions were valued, so what the UI shows is what the total was built with.
 */
object FxRates {

    /**
     * One line per foreign currency among [currencies], against [reference], at [at], sorted by
     * currency code. Blank codes, the reference itself and any currency whose rate is missing are
     * dropped - a portfolio held entirely in its reference currency yields an empty list, which the
     * UI renders as nothing at all.
     */
    fun held(
        currencies: Collection<String>,
        reference: String,
        fx: Map<String, PriceSeries>,
        at: LocalDate,
    ): List<FxRate> {
        val converter = Converter(fx)
        val ref = reference.uppercase()
        return currencies
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != ref }
            .distinct()
            .sorted()
            .mapNotNull { code ->
                val rate = converter.rate(code, ref, at) ?: return@mapNotNull null
                FxRate(code, ref, rate, observedAt(code, ref, fx, at))
            }
    }

    /**
     * The date the cross is really as of: both legs are forward-filled independently, so the pair is
     * only as fresh as the older of the two. The USD pivot is a constant and carries no date.
     */
    private fun observedAt(base: String, quote: String, fx: Map<String, PriceSeries>, at: LocalDate): LocalDate? {
        val dates = listOf(base, quote)
            .filter { it != Converter.USD }
            .mapNotNull { fx[it]?.at(at)?.second }
        return dates.minOrNull()
    }
}
