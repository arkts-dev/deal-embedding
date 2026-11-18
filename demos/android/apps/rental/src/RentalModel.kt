package dev.deal.apps.rental

import android.content.Context
import org.json.*
import java.time.*

val RENTAL_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

data class RentalAttributes(
    val kind: String, val adjustable: Boolean = false, val clipAdapter: Boolean = false,
    val balanced: Boolean = false, val connectorFrom: String = "", val connectorTo: String = "", val lengthMm: Int = 0,
)
data class RentalItem(
    val id: String, val name: String, val category: String, val specification: String,
    val included: List<String>, val notIncluded: List<String>, val quantity: Int, val cents: Int, val art: String,
    val attributes: RentalAttributes, val components: List<QuoteLine> = emptyList(),
)
data class QuoteLine(val itemId: String, val quantity: Int)
data class Quote(val id: String, val lines: List<QuoteLine>, val from: Long, val until: Long, val expiresAt: Long, val substitution: String)
data class Reservation(
    val id: String, val quoteId: String, val show: String, val lines: List<QuoteLine>, val from: Long, val until: Long,
    val collector: String, val depositCents: Int = 5000, val collected: List<String> = emptyList(), val returned: Boolean = false, val cancelled: Boolean = false,
)

class RentalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("rental", 0)

    val catalogue: List<RentalItem> = listOf(
        RentalItem("stand", "Boom microphone stand", "Microphones", "Adjustable boom; adapter fitting the venue microphone clip",
            listOf("Boom stand", "Clip adapter"), listOf("Microphone"), 2, 800, "stand",
            RentalAttributes("stand", adjustable = true, clipAdapter = true)),
        RentalItem("cable10", "Vocal cable 10 m", "Cables", "Balanced XLR female → XLR male, 10 m",
            listOf("10 m balanced XLR cable"), emptyList(), 3, 500, "cable",
            RentalAttributes("cable", balanced = true, connectorFrom = "XLR female", connectorTo = "XLR male", lengthMm = 10000)),
        RentalItem("package", "Vocal package", "Microphones", "One boom stand plus one 10 m cable",
            listOf("1 boom microphone stand", "1 clip adapter", "1 XLR female → XLR male cable, 10 m"), listOf("Microphone"), 2, 1200, "package",
            RentalAttributes("package"), listOf(QuoteLine("stand", 1), QuoteLine("cable10", 1))),
        RentalItem("cable3", "Vocal cable 3 m", "Cables", "Balanced XLR female → XLR male, 3 m",
            listOf("3 m balanced XLR cable"), emptyList(), 2, 300, "cable",
            RentalAttributes("cable", balanced = true, connectorFrom = "XLR female", connectorTo = "XLR male", lengthMm = 3000)),
        RentalItem("amp", "Bass combo amplifier", "Backline", "At least 300 W into its built-in speaker; 6.35 mm instrument input; balanced XLR DI output",
            listOf("Amplifier", "Power cable"), listOf("Instrument cable"), 1, 4000, "amp", RentalAttributes("amplifier")),
    )

    fun item(id: String) = catalogue.firstOrNull { it.id == id }
    fun quotes(): List<Quote> = decode("quotes") { o -> Quote(o.getString("id"), lines(o),
        o.optLong("from"), o.optLong("until"), o.optLong("expiresAt"), o.optString("substitution")) }
    fun reservations(): List<Reservation> = decode("reservations") { o -> Reservation(o.getString("id"), o.optString("quoteId"), o.optString("show"),
        lines(o), o.optLong("from"), o.optLong("until"), o.optString("collector"), o.optInt("depositCents", 5000),
        (0 until (o.optJSONArray("collected")?.length() ?: 0)).map { o.getJSONArray("collected").getString(it) }, o.optBoolean("returned"), o.optBoolean("cancelled")) }
    fun saveQuotes(value: List<Quote>) { prefs.edit().putString("quotes", JSONArray(value.map { it.json() }).toString()).commit() }
    fun saveReservations(value: List<Reservation>) { prefs.edit().putString("reservations", JSONArray(value.map { it.json() }).toString()).commit() }
    fun lineTotal(line: QuoteLine) = (item(line.itemId)?.cents ?: 0) * line.quantity
    fun quoteTotal(quote: Quote) = quote.lines.sumOf(::lineTotal)

    /** A package consumes the stand and cable it contains rather than extra stock. */
    fun consumed(itemId: String, quantity: Int): Map<String, Int> {
        val components = checkNotNull(item(itemId)).components
        return if (components.isEmpty()) mapOf(itemId to quantity)
        else components.associate { it.itemId to it.quantity * quantity }
    }

    fun availability(from: Long, until: Long): Map<String, Int> {
        val used = mutableMapOf<String, Int>()
        reservations().filter { !it.cancelled && it.from < until && from < it.until }.forEach { reservation ->
            reservation.lines.forEach { line -> consumed(line.itemId, line.quantity).forEach { (id, count) -> used[id] = (used[id] ?: 0) + count } }
        }
        val remaining = catalogue.associate { it.id to ((it.quantity - (used[it.id] ?: 0)).coerceAtLeast(0)) }
        return remaining.mapValues { (id, count) ->
            checkNotNull(item(id)).components.fold(count) { available, component ->
                minOf(available, remaining.getValue(component.itemId) / component.quantity)
            }
        }
    }

    private fun lines(o: JSONObject): List<QuoteLine> {
        val array = o.optJSONArray("lines") ?: JSONArray()
        return (0 until array.length()).map { QuoteLine(array.getJSONObject(it).getString("itemId"), array.getJSONObject(it).optInt("quantity", 1)) }
    }
    private fun <T> decode(key: String, parse: (JSONObject) -> T): List<T> =
        prefs.getString(key, null)?.let { runCatching { val a = JSONArray(it); (0 until a.length()).map { i -> parse(a.getJSONObject(i)) } }.getOrNull() } ?: emptyList()
    private fun Quote.json() = JSONObject().put("id", id).put("lines", JSONArray(lines.map { JSONObject().put("itemId", it.itemId).put("quantity", it.quantity) }))
        .put("from", from).put("until", until).put("expiresAt", expiresAt).put("substitution", substitution)
    private fun Reservation.json() = JSONObject().put("id", id).put("quoteId", quoteId).put("show", show)
        .put("lines", JSONArray(lines.map { JSONObject().put("itemId", it.itemId).put("quantity", it.quantity) }))
        .put("from", from).put("until", until).put("collector", collector).put("depositCents", depositCents)
        .put("collected", JSONArray(collected)).put("returned", returned).put("cancelled", cancelled)
}
