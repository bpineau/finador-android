package fin.android.ui

import fin.android.market.FxRate
import fin.android.market.Quotes
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val moneySymbols = DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ' '
    decimalSeparator = '.'
}
private val moneyFormat = DecimalFormat("#,##0.00", moneySymbols)
private val wholeMoneyFormat = DecimalFormat("#,##0", moneySymbols)
private val percentFormat = DecimalFormat("#,##0.0#", moneySymbols)
private val ratioFormat = DecimalFormat("#,##0.00", moneySymbols)
private val gainCellFormat = DecimalFormat("#,##0.0", moneySymbols)
private val fxRateFormat = DecimalFormat("#,##0.0000", moneySymbols)
private val offHoursClock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Formats a Double as "1 234.56 CCY" (thousands-grouped, two decimals). */
fun formatMoney(value: Double, ccy: String): String = "${moneyFormat.format(value)} $ccy"

/** Formats a Double amount without a currency suffix. */
fun formatAmount(value: Double): String = moneyFormat.format(value)

/** Formats a money magnitude as a grouped whole number: no decimals, no ccy. E.g. 15245.6 → "15 245". */
fun formatWholeAmount(value: Double): String = wholeMoneyFormat.format(value)

/** Formats a fraction (0.123 → "12.3%"); null → "-". 1-2 decimals. */
fun formatPercent(fraction: Double?): String =
    if (fraction == null) "-" else "${percentFormat.format(fraction * 100)}%"

/** Formats a unitless ratio (e.g. Sharpe) with two decimals; null → "-". */
fun formatRatio(value: Double?): String = if (value == null) "-" else ratioFormat.format(value)

/**
 * Formats a money amount with a leading minus for losses and NO "+" for gains (color carries the
 * direction): "1 234.56 CCY" / "−1 234.56 CCY"; null → "-".
 */
fun formatSignedMoney(value: Double?, ccy: String): String {
    if (value == null) return "-"
    val sign = if (value < 0) "−" else ""
    return "$sign${moneyFormat.format(kotlin.math.abs(value))} $ccy"
}

/** Formats a percentage fraction with a leading minus for losses and NO "+" for gains; null → "-". */
fun formatSignedPercent(fraction: Double?): String {
    if (fraction == null) return "-"
    val sign = if (fraction < 0) "−" else ""
    return "$sign${percentFormat.format(kotlin.math.abs(fraction) * 100)}%"
}

/**
 * Compact gains-table cell: grouped thousands, exactly one decimal, a leading minus for negatives,
 * NO leading "+" and NO currency code (the column's currency is implicit). E.g. 1234.56 → "1 234.6",
 * −12.34 → "-12.3", 0.04 → "0.0".
 */
fun formatGainCell(value: Double): String = gainCellFormat.format(value)

/** Like [formatGainCell] but null → "-". */
fun formatGainCellOrDash(value: Double?): String = if (value == null) "-" else gainCellFormat.format(value)

/** A percentage with exactly one decimal, no "+", no currency; null → "-". E.g. 0.123 → "12.3%". */
fun formatGainPercent(fraction: Double?): String =
    if (fraction == null) "-" else "${gainCellFormat.format(fraction * 100)}%"

/**
 * States an exchange rate the way the valuation used it: "1 USD = 0.8543 EUR", four decimals, with
 * the day it was observed appended when the series carries one.
 */
fun formatFxRate(r: FxRate): String {
    val head = "1 ${r.base} = ${fxRateFormat.format(r.rate)} ${r.quote}"
    return if (r.asOf == null) head else "$head (${r.asOf})"
}

/**
 * Labels an off-hours print the way the line states it: "pre 08:14" / "post 19:59", the session
 * Yahoo named and the instant it was struck, in the DEVICE's zone (a New York after-hours print
 * reads at the hour the holder is living, which is the whole point of showing the clock).
 */
fun formatOffHours(p: Quotes.OffHoursPrint): String {
    val at = Instant.ofEpochSecond(p.time).atZone(ZoneId.systemDefault())
    return "${p.session} ${offHoursClock.format(at)}"
}

/** Formats a holding quantity: trailing zeros trimmed, with a "units" suffix. E.g. 10.50 → "10.5 units". */
fun formatQuantity(qty: BigDecimal): String = "${qty.stripTrailingZeros().toPlainString()} units"
