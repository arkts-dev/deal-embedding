package dev.deal.embedding

fun main() {
    val locale = java.util.Locale.US
    fun same(value: Double, expected: String, currency: String = "EUR") {
        check(formatMoney(value,currency,locale) == expected) { "Money rendering changed for $value: ${formatMoney(value,currency,locale)}" }
    }
    same(1750.0,"€17.50")
    same(-1000.0,"-€10.00")
    same(0.0,"€0.00")
    same(-0.0,"€0.00")
    same(2147483648.0,"€21,474,836.48")
    same(-2147483649.0,"-€21,474,836.49")
    same(9007199254740991.0,"€90,071,992,547,409.91")
    same(-9007199254740991.0,"-€90,071,992,547,409.91")
    same(1750.0,"¥1,750","JPY")
    same(1750.0,"KWD1.750","KWD")
    for(value in listOf(1.5,9007199254740992.0,-9007199254740992.0,Double.NaN,Double.POSITIVE_INFINITY)) {
        check(runCatching { formatMoney(value,"EUR",locale) }.isFailure) { "Invalid money accepted: $value" }
    }
    println("PASS Money presentation: signed safe-integer bounds, exact minor-unit scale, currency digits and invalid rejection")
}
