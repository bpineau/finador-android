package fin.android.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val moneySymbols = DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ' '
    decimalSeparator = '.'
}
private val moneyFormat = DecimalFormat("#,##0.00", moneySymbols)

/** Formats a Double as "1 234.56 CCY" (thousands-grouped, two decimals). */
fun formatMoney(value: Double, ccy: String): String = "${moneyFormat.format(value)} $ccy"

/** Formats a Double amount without a currency suffix. */
fun formatAmount(value: Double): String = moneyFormat.format(value)
