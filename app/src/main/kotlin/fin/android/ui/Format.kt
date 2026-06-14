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

/** Formats a Double as "1 234.56 CCY" (thousands-grouped, two decimals). */
fun formatMoney(value: Double, ccy: String): String = "${moneyFormat.format(value)} $ccy"

/** Formats a Double amount without a currency suffix. */
fun formatAmount(value: Double): String = moneyFormat.format(value)

/** Formats a fraction (0.123 → "12.3%"); null → "—". 1–2 decimals. */
fun formatPercent(fraction: Double?): String =
    if (fraction == null) "—" else "${percentFormat.format(fraction * 100)}%"

/** Formats a unitless ratio (e.g. Sharpe) with two decimals; null → "—". */
fun formatRatio(value: Double?): String = if (value == null) "—" else ratioFormat.format(value)
