package fin.android.market

/**
 * Venue SUB-UNITS: the codes a provider reports where a currency is expected, for a price quoted in
 * a hundredth of that currency.
 *
 * Yahoo answers `"currency":"GBp"` for a London listing and prices it in PENCE; the Financial Times
 * spells the same thing `GBX`; Johannesburg is `ZAc` (cents), Tel Aviv `ILA` (agorot), some US
 * futures `USX` (US cents). The number and the code then disagree by a hundredfold, and no
 * plausibility check can see it: rescaling a whole series leaves every return untouched.
 *
 * `GBp` and `GBP` differ BY CASE ALONE, which makes this a trap in both directions:
 *
 * - a case-INSENSITIVE comparison (`equals(ignoreCase = true)`, `uppercase()`) folds the sub-unit
 *   into the currency and books a pence price as pounds - a 100x valuation error;
 * - a case-SENSITIVE one, which is what this app used to do, rejects the series instead, and a
 *   London holding then falls silently back to its cost basis for ever.
 *
 * So the sub-unit is removed where a provider's numbers enter the app ([normalize]), never carried
 * and remembered, and every currency comparison goes through [same]. Mirrors the Go reference's
 * `pkg/marketdata/units.go`.
 *
 * The table is keyed on the EXACT spellings observed, never folded to one case: a case-insensitive
 * lookup would divide every pound price by a hundred.
 */
internal object Units {

    private data class Minor(val currency: String, val factor: Double)

    private val MINOR: Map<String, Minor> = mapOf(
        "GBp" to Minor("GBP", 0.01), // Yahoo, London pence
        "GBX" to Minor("GBP", 0.01), // Financial Times, same pence
        "GBx" to Minor("GBP", 0.01),
        "ZAc" to Minor("ZAR", 0.01), // Johannesburg cents
        "ZAC" to Minor("ZAR", 0.01),
        "ILA" to Minor("ILS", 0.01), // Tel Aviv agorot
        "USX" to Minor("USD", 0.01), // US cents (CBOT grains, ICE softs)
    )

    /**
     * The ISO currency [code] really measures, and the factor turning one quoted unit into one of
     * those. An ordinary code returns itself and 1, so callers can apply the result unconditionally;
     * a null code stays null (a provider that discloses no currency, such as Morningstar).
     */
    fun major(code: String?): Pair<String?, Double> {
        val m = MINOR[code?.trim()] ?: return code to 1.0
        return m.currency to m.factor
    }

    /** Whether [code] is a venue sub-unit rather than an ISO currency. */
    fun isMinor(code: String?): Boolean = MINOR.containsKey(code?.trim())

    /**
     * Whether two quote-currency codes name the same money, ignoring case AND the venue sub-unit:
     * `GBp`, `GBX` and `gbp` all answer for `GBP`. A null on either side is "unknown", which is not
     * a mismatch - the provider simply did not say (Morningstar never does).
     *
     * It is a question about the LABEL: the numbers are made to agree with it by [normalize], at the
     * provider boundary, never here.
     */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        val (ma, _) = major(a)
        val (mb, _) = major(b)
        return ma.equals(mb, ignoreCase = true)
    }

    /**
     * [data] rescaled into its major currency: closes and dividends multiplied by the factor, the
     * currency relabelled. A series already in an ordinary currency is returned untouched, so the
     * call is idempotent and free.
     *
     * [DailyData.openFactors] is deliberately left alone: an open-to-close RATIO carries no
     * currency, and dividing it by a hundred would destroy the nowcast anchor it exists for.
     */
    fun normalize(data: DailyData): DailyData {
        if (!isMinor(data.currency)) return data
        val (currency, factor) = major(data.currency)
        return data.copy(
            currency = currency,
            closes = data.closes.map { it.copy(close = it.close * factor) },
            dividends = data.dividends.map { it.copy(amount = it.amount * factor) },
        )
    }

    /** [normalize] for a single live quote, off-hours print included (same venue, same unit). */
    fun normalize(quote: Quote): Quote {
        if (!isMinor(quote.currency)) return quote
        val (currency, factor) = major(quote.currency)
        return quote.copy(
            price = quote.price * factor,
            currency = currency,
            offHours = quote.offHours?.let { it.copy(price = it.price * factor) },
        )
    }
}
