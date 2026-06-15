package fin.android.domain

import java.math.BigDecimal

/** Exact monetary amount in a currency. Decimals are arbitrary-precision (never floats). */
data class Money(val amount: BigDecimal, val ccy: String)

/** Asset nature. Wire form is the lowercase string (MarshalText on the Go side). */
enum class AssetKind(val wire: String) {
    SECURITY("security"),
    PROPERTY("property");

    companion object {
        fun fromWire(s: String): AssetKind =
            entries.firstOrNull { it.wire == s } ?: error("unknown asset kind: $s")
    }
}

/** Transaction nature. Enum names equal the wire strings. */
enum class TxKind {
    buy, sell, dividend, fee, deposit, withdraw, statement;

    companion object {
        fun fromWire(s: String): TxKind =
            entries.firstOrNull { it.name == s } ?: error("unknown tx kind: $s")
    }
}

/**
 * Per-account tax envelope. Wire forms: `none`, `gains:N%`, `value:N%`, where N is the rate
 * times 100 (the stored rate is a fraction, e.g. 0.172 ↔ "gains:17.2%").
 */
sealed class TaxRule {
    object None : TaxRule()
    data class Gains(val rate: BigDecimal) : TaxRule()
    data class Value(val rate: BigDecimal) : TaxRule()

    fun toWire(): String = when (this) {
        is None -> "none"
        is Gains -> "gains:${ratePercent(rate)}%"
        is Value -> "value:${ratePercent(rate)}%"
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        fun fromWire(s: String): TaxRule {
            if (s == "none") return None
            val i = s.indexOf(':')
            require(i > 0 && s.endsWith("%")) { "bad tax rule: $s" }
            val kind = s.substring(0, i)
            val pct = BigDecimal(s.substring(i + 1, s.length - 1))
            val rate = pct.divide(HUNDRED)
            return when (kind) {
                "gains" -> Gains(rate)
                "value" -> Value(rate)
                else -> error("bad tax rule kind: $kind")
            }
        }
    }
}

/** A tax-rate fraction (0.172) rendered as a percentage number string ("17.2"), trailing zeros trimmed. */
internal fun ratePercent(fraction: BigDecimal): String =
    fraction.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()
