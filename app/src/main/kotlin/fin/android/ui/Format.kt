package fin.android.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val moneySymbols = DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ' '
    decimalSeparator = '.'
}
private val moneyFormat = DecimalFormat("#,##0.00", moneySymbols)
private val percentFormat = DecimalFormat("#,##0.0#", moneySymbols)
private val ratioFormat = DecimalFormat("#,##0.00", moneySymbols)
private val gainCellFormat = DecimalFormat("#,##0.0", moneySymbols)

/** Formats a Double as "1 234.56 CCY" (thousands-grouped, two decimals). */
fun formatMoney(value: Double, ccy: String): String = "${moneyFormat.format(value)} $ccy"

/** Formats a Double amount without a currency suffix. */
fun formatAmount(value: Double): String = moneyFormat.format(value)

/** Formats a fraction (0.123 → "12.3%"); null → "—". 1–2 decimals. */
fun formatPercent(fraction: Double?): String =
    if (fraction == null) "—" else "${percentFormat.format(fraction * 100)}%"

/** Formats a unitless ratio (e.g. Sharpe) with two decimals; null → "—". */
fun formatRatio(value: Double?): String = if (value == null) "—" else ratioFormat.format(value)

/**
 * Formats a signed money amount: "+1 234.56 CCY" / "−1 234.56 CCY"; null → "—".
 * Uses a real minus sign and a leading "+" so a gain reads as such at a glance.
 */
fun formatSignedMoney(value: Double?, ccy: String): String {
    if (value == null) return "—"
    val sign = if (value < 0) "−" else "+"
    return "$sign${moneyFormat.format(kotlin.math.abs(value))} $ccy"
}

/** Formats a signed percentage fraction: "+12.3%" / "−4.5%"; null → "—". */
fun formatSignedPercent(fraction: Double?): String {
    if (fraction == null) return "—"
    val sign = if (fraction < 0) "−" else "+"
    return "$sign${percentFormat.format(kotlin.math.abs(fraction) * 100)}%"
}

/**
 * Compact gains-table cell: grouped thousands, exactly one decimal, a leading minus for negatives,
 * NO leading "+" and NO currency code (the column's currency is implicit). E.g. 1234.56 → "1 234.6",
 * −12.34 → "-12.3", 0.04 → "0.0".
 */
fun formatGainCell(value: Double): String = gainCellFormat.format(value)

/** Like [formatGainCell] but null → "—". */
fun formatGainCellOrDash(value: Double?): String = if (value == null) "—" else gainCellFormat.format(value)

/** A percentage with exactly one decimal, no "+", no currency; null → "—". E.g. 0.123 → "12.3%". */
fun formatGainPercent(fraction: Double?): String =
    if (fraction == null) "—" else "${gainCellFormat.format(fraction * 100)}%"
