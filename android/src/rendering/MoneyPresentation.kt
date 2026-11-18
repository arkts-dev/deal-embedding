package dev.deal.embedding

/** Presentation only. Preserve exact safe-integer minor units; never narrow to Int. */
internal fun formatMoney(minorUnits: Double, currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    require(minorUnits.isFinite() && minorUnits == kotlin.math.floor(minorUnits) && kotlin.math.abs(minorUnits) <= 9007199254740991.0) {
        "Expected safe integral minor units"
    }
    val unit = java.util.Currency.getInstance(currency)
    val digits = unit.defaultFractionDigits.coerceAtLeast(0)
    val amount = java.math.BigDecimal.valueOf(minorUnits.toLong(), digits)
    return java.text.NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = unit
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.format(amount)
}
