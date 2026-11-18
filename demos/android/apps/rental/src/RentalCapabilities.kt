package dev.deal.apps.rental

import dev.deal.embedding.*
import dev.deal.embedding.WireField
import android.content.Context
import dev.deal.embedding.capabilities.*
import org.json.*

/** The called subset: equipment search, availability, quote and reservation outcomes. */
fun rentalCapabilities(context: Context): NativeCapabilities {
    val store = RentalStore(context)
    val contract = CapabilityContract("host/rental", "Rental catalogue, availability, quotes and reservations", listOf(
        CapabilityFunction("equipment", "Read before quoting. Match authoritative attributes, not display prose: kind=stand/cable/package/amplifier; lengthMm is millimetres; connectorFrom/To are canonical endpoint labels (e.g. XLR female/XLR male). Package components reference catalogue item IDs and quantities; inspect their attributes. Empty components means a single item. available is quantity; cents is EUR hire. from/until are offset-bearing ISO instants, until>from. Packages consume component stock.", listOf(
            Parameter("from", WireType("string")), Parameter("until", WireType("string"))),
            WireType("array", maximum = 32, element = WireType("record", fields = listOf(WireField("id", WireType("string")), WireField("name", WireType("string")), WireField("specification", WireType("string")), WireField("cents", WireType("number")), WireField("available", WireType("number")), WireField("included", WireType("string")), WireField("category", WireType("string")), WireField("attributes", WireType("record", fields = listOf(WireField("kind", WireType("string")), WireField("adjustable", WireType("boolean")), WireField("clipAdapter", WireType("boolean")), WireField("balanced", WireType("boolean")), WireField("connectorFrom", WireType("string")), WireField("connectorTo", WireType("string")), WireField("lengthMm", WireType("number", minimumNumber = 0.0))))), WireField("components", WireType("array", maximum = 16, element = WireType("record", fields = listOf(WireField("itemId", WireType("string")), WireField("quantity", WireType("number", minimumNumber = 1.0, maximumNumber = 32.0)))))))))),
        CapabilityFunction("quote", "Read-only whole-basket quote. First read equipment. items is nonempty comma-separated catalogue ID:quantity pairs; distinct IDs, quantities 1..32. Submit each alternative as ONE basket: package ID versus component IDs together. cents/depositCents/availability/expiry apply to that basket; do NOT sum independent quotes or deposits. depositCents is refundable, separate from hire. available=false is unusable. from/until are offset-bearing ISO instants, until>from.", listOf(
            Parameter("items", WireType("string")), Parameter("from", WireType("string")), Parameter("until", WireType("string"))),
            WireType("record", fields = listOf(WireField("cents", WireType("number")), WireField("depositCents", WireType("number")), WireField("expiresInMinutes", WireType("number")), WireField("terms", WireType("string")), WireField("available", WireType("boolean"))))),
        CapabilityFunction("proposeReservation", "Stage a reservation for native review", listOf(
            Parameter("items", WireType("string")), Parameter("from", WireType("string")),
            Parameter("until", WireType("string")), Parameter("collector", WireType("string"))),
            WireType("record", fields = listOf(WireField("proposalId", WireType("string")), WireField("state", WireType("string"))))),
        CapabilityFunction("outcome", "Read a reservation proposal outcome", listOf(Parameter("proposalId", WireType("string"))),
            WireType("record", fields = listOf(WireField("state", WireType("string")), WireField("reservationId", WireType("string")), WireField("detail", WireType("string"))))),
    ))
    fun period(args: JSONArray, start: Int): Pair<Long, Long> {
        val from = java.time.Instant.parse(args.getString(start)).toEpochMilli()
        val until = java.time.Instant.parse(args.getString(start + 1)).toEpochMilli()
        require(until > from) { "Rental period must end after it starts" }
        return from to until
    }
    return NativeCapabilities(contract, mapOf(
        "equipment" to { args ->
            val (from, until) = period(args, 0)
            val available = store.availability(from, until)
            JSONArray(store.catalogue.map { item -> rentalCatalogueRecord(item, available[item.id] ?: 0) })
        },
        "quote" to { args ->
            val lines = parseLines(args.getString(0))
            require(lines.all { store.item(it.first) != null }) { "Unknown rental item" }
            val (from, until) = period(args, 1)
            val available = store.availability(from, until)
            val consumed = mutableMapOf<String, Int>()
            lines.forEach { line -> store.consumed(line.first, line.second).forEach { (id, count) -> consumed[id] = (consumed[id] ?: 0) + count } }
            JSONObject().put("cents", lines.sumOf { checkNotNull(store.item(it.first)).cents * it.second })
                .put("depositCents", 5000).put("expiresInMinutes", 15)
                .put("terms", "Collection only. Exact items reserved; substitutions need approval.")
                .put("available", lines.all { (available[it.first] ?: 0) >= it.second } && consumed.all { (id, count) -> (available[id] ?: 0) >= count })
        },
        "proposeReservation" to { args ->
            val prefs = context.getSharedPreferences("proposals", 0)
            val id = "rental-" + java.util.UUID.randomUUID().toString().take(8)
            prefs.edit().putString("rental:$id", JSONObject().put("proposalId", id).put("state", "pending")
                .put("items", args.getString(0)).put("from", args.getString(1)).put("until", args.getString(2)).put("collector", args.getString(3)).toString()).commit()
            JSONObject().put("proposalId", id).put("state", "pending")
        },
        "outcome" to { args -> dev.deal.shell.proposalOutcome(context, "rental", args.getString(0), "reservationId") },
    ))
}

internal fun rentalCatalogueRecord(item: RentalItem, available: Int): JSONObject {
    val a = item.attributes
    return JSONObject().put("id", item.id).put("name", item.name).put("specification", item.specification)
        .put("cents", item.cents).put("available", available).put("included", item.included.joinToString("; ")).put("category", item.category)
        .put("attributes", JSONObject().put("kind", a.kind).put("adjustable", a.adjustable).put("clipAdapter", a.clipAdapter)
            .put("balanced", a.balanced).put("connectorFrom", a.connectorFrom).put("connectorTo", a.connectorTo).put("lengthMm", a.lengthMm))
        .put("components", JSONArray(item.components.map { JSONObject().put("itemId", it.itemId).put("quantity", it.quantity) }))
}

internal fun parseLines(text: String): List<Pair<String, Int>> {
    val entries = text.split(",")
    require(entries.size in 1..16) { "Expected 1..16 rental lines" }
    val lines = entries.map { entry ->
        val parts = entry.trim().split(":")
        require(parts.size == 2 && parts[0].trim().isNotEmpty()) { "Expected itemId:quantity" }
        val quantity = parts[1].trim().toIntOrNull()
        require(quantity != null && quantity in 1..32) { "Invalid rental quantity" }
        parts[0].trim() to quantity
    }
    require(lines.map { it.first }.distinct().size == lines.size) { "Duplicate rental item" }
    return lines
}
