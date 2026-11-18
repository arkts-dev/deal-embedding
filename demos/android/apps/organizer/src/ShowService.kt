package dev.deal.apps.organizer

import dev.deal.embedding.*
import dev.deal.shell.SharedAppService
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Cross-app publication is read-only; native review staging remains local to Organizer. */
class ShowService : SharedAppService() {
    override val capabilities by lazy {
        val text = WireType("string", maximum = 1024)
        val fields = listOf("id", "show", "date", "venue", "act", "name", "specification", "from", "until", "location", "status").map { WireField(it, text) } + WireField("quantity", WireType("number"))
        nativeCapabilities(CapabilityContract("host/show", "Shared synthetic show requirements, exact quantities, specifications and deadlines", functions = listOf(
            CapabilityFunction("requirements", "Read the shared show plan, including unresolved equipment and catering needs", result = WireType("array", maximum = 32, element = WireType("record", fields = fields)))
        )), mapOf("requirements" to { _ ->
            val show = ShowStore(this).load()
            JSONArray(show?.requirements?.take(32)?.map { requirement -> JSONObject()
                .put("id", requirement.id).put("show", show.title).put("date", show.date.toString()).put("venue", show.venue)
                .put("act", requirement.act).put("name", requirement.name).put("quantity", requirement.quantity)
                .put("specification", requirement.specification).put("from", Instant.ofEpochMilli(requirement.from).toString())
                .put("until", Instant.ofEpochMilli(requirement.until).toString()).put("location", requirement.location).put("status", requirement.summary) } ?: emptyList<JSONObject>())
        }))
    }
}
