package dev.deal.apps.pizza

import android.content.Context
import org.json.*
import java.time.*
import java.util.UUID

val PIZZA_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

data class MenuItem(val id: String, val name: String, val size: String, val cents: Int, val vegan: Boolean, val vegetarian: Boolean, val ingredients: String, val allergens: String, val diameterMm: Int)
data class DeliveryWindow(val id: String, val from: LocalTime, val until: LocalTime, val guaranteed: Boolean)
data class Order(
    val id: String, val show: String, val lines: Map<String, Int>, val windowId: String, val address: String, val instructions: String,
    val recipient: String, val foodCents: Int, val deliveryCents: Int, val status: String = "Confirmed", val purchased: Boolean = false,
) {
    val totalCents: Int get() = foodCents + deliveryCents
}

class PizzaStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pizza", 0)

    val menu: List<MenuItem> = listOf(
        MenuItem("margherita", "Margherita", "30 cm", 1000, vegan = false, vegetarian = true, "Tomato, mozzarella, basil", "Milk, wheat", diameterMm = 300),
        MenuItem("garden", "Garden Vegan", "30 cm", 1200, vegan = true, vegetarian = true, "Tomato, plant-based cheese, seasonal vegetables", "Wheat", diameterMm = 300),
        MenuItem("pepperoni", "Pepperoni", "30 cm", 1300, vegan = false, vegetarian = false, "Tomato, mozzarella, pepperoni", "Meat, milk, wheat", diameterMm = 300),
    )

    val windows: List<DeliveryWindow> = listOf(
        DeliveryWindow("w1", LocalTime.of(17, 45), LocalTime.of(18, 15), guaranteed = true),
        DeliveryWindow("w2", LocalTime.of(18, 15), LocalTime.of(18, 45), guaranteed = false),
        DeliveryWindow("w3", LocalTime.of(18, 45), LocalTime.of(19, 15), guaranteed = false),
    )

    val deliveryCents = 500
    val address = "The Foundry, 12 Foundry Lane, Berlin"
    val instructions = "Use the stage entrance. Deliver to Static Bloom's dressing room."
    val recipient = "Morgan (demo contact)"

    fun item(id: String) = menu.firstOrNull { it.id == id }
    fun window(id: String) = windows.firstOrNull { it.id == id }
    fun foodTotal(lines: Map<String, Int>) = lines.entries.sumOf { (id, count) -> (item(id)?.cents ?: 0) * count }
    fun veganCount(lines: Map<String, Int>) = lines.filterKeys { item(it)?.vegan == true }.values.sum()
    fun totalCount(lines: Map<String, Int>) = lines.values.sum()
    fun orders(): List<Order> {
        val text = prefs.getString("orders", null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                val lines = o.optJSONObject("lines") ?: JSONObject()
                val quantities = lines.keys().asSequence().associateWith { lines.getInt(it) }
                Order(o.getString("id"), o.optString("show"), quantities, o.optString("windowId"), o.optString("address"),
                    o.optString("instructions"), o.optString("recipient"), o.optInt("foodCents"), o.optInt("deliveryCents"),
                    o.optString("status", "Confirmed"), o.optBoolean("purchased"))
            }
        }.getOrNull()
        return parsed ?: emptyList()
    }
    fun saveOrders(value: List<Order>) {
        val array = JSONArray()
        value.forEach { order ->
            val lines = JSONObject()
            order.lines.forEach { (id, count) -> lines.put(id, count) }
            array.put(JSONObject().put("id", order.id).put("show", order.show).put("lines", lines).put("windowId", order.windowId)
                .put("address", order.address).put("instructions", order.instructions).put("recipient", order.recipient)
                .put("foodCents", order.foodCents).put("deliveryCents", order.deliveryCents).put("status", order.status).put("purchased", order.purchased))
        }
        prefs.edit().putString("orders", array.toString()).commit()
    }
    fun newOrderId() = UUID.randomUUID().toString()
}
