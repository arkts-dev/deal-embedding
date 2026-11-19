package dev.deal.embedding

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The shared numeric presentation field rule. Not a prop-value class: the pack build,
 * candidate admission and ingress apply it at the component site naming component and prop.
 */
internal object NumericPresentation {
    fun validDecimals(value: Double): Boolean =
        value.isFinite() && (value == -1.0 || (value == kotlin.math.floor(value) && value >= 0 && value <= 6))

    fun validMinimumDigits(value: Double): Boolean =
        value.isFinite() && value == kotlin.math.floor(value) && value >= 1

    /** Pure display rule: shortest round-tripping or fixed decimals, digit padding, affixes. */
    fun format(value: Double, prefix: String, suffix: String, minimumDigits: Int, decimals: Int): String {
        require(value.isFinite()) { "Numeric presentation requires a finite value" }
        val plain = if (decimals < 0) {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        } else {
            BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        }
        val negative = plain.startsWith("-")
        val digits = if (negative) plain.substring(1) else plain
        val point = digits.indexOf('.')
        val whole = if (point < 0) digits else digits.substring(0, point)
        val fraction = if (point < 0) "" else digits.substring(point + 1)
        val padded = whole.padStart(minimumDigits.coerceAtLeast(1), '0')
        val sign = if (negative) "-" else ""
        return prefix + sign + padded + (if (fraction.isEmpty()) "" else "." + fraction) + suffix
    }

    /** Display text of a numeric-presentation node from its resolved values. */
    fun display(value: Double, props: Map<String, UiValue>): String {
        fun text(name: String) = (props[name] as? UiValue.Text)?.value ?: ""
        fun number(name: String, fallback: Double) = (props[name] as? UiValue.Num)?.value ?: fallback
        return format(value, text("prefix"), text("suffix"), number("minimumDigits", 1.0).toInt(), number("decimals", -1.0).toInt())
    }

    /** Domain fault for a declared presentation field, naming the component and the prop. */
    fun fault(component: String, prop: String, value: Double): IngressFault? {
        val valid = when (prop) {
            "decimals" -> validDecimals(value)
            "minimumDigits" -> validMinimumDigits(value)
            else -> return null
        }
        return if (valid) null else IngressFault("PROP_FAULT", component, prop, null, null, "presentation")
    }
}
