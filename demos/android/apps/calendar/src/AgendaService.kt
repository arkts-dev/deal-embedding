package dev.deal.apps.calendar

import dev.deal.embedding.*
import dev.deal.shell.SharedAppService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant

/** Only dedicated Gig Demo records; publication grants neither personal-calendar nor write access. */
class AgendaService : SharedAppService() {
    override val capabilities by lazy {
        val text = WireType("string", maximum = 2048)
        val row = WireType("record", fields = listOf("id", "title", "from", "until", "location", "notes").map { WireField(it, text) })
        nativeCapabilities(CapabilityContract("host/agenda", "Read the shared synthetic Gig Demo schedule; never personal calendars or writes", functions = listOf(
            CapabilityFunction("events", "Read dedicated demo events on an ISO local date in Europe/Berlin", listOf(Parameter("date", WireType("string", maximum = 10))), WireType("array", maximum = 32, element = row))
        )), mapOf("events" to { args ->
            val day = LocalDate.parse(args.getString(0))
            JSONArray(ShowAgenda(this).events(day).take(32).map { event -> JSONObject()
                .put("id", event.id.toString()).put("title", event.title).put("from", Instant.ofEpochMilli(event.from).toString())
                .put("until", Instant.ofEpochMilli(event.until).toString()).put("location", event.location).put("notes", event.notes.take(2048)) })
        }))
    }
}
