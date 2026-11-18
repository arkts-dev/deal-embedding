package dev.deal.apps.organizer

import org.json.*
import java.time.*
import java.util.UUID

/** Amounts are whole cents; instants are epoch milliseconds resolved against the show zone. */
val SHOW_ZONE: ZoneId = ZoneId.of("Europe/Berlin")


data class Money(val cents: Int) { val text: String get() = "€%.2f".format(cents / 100.0) }

enum class Coverage { Covered, Missing, Conflict }
enum class Commitment { None, Proposed, Confirmed, Declined }

data class Requirement(
    val id: String,
    val act: String,
    val department: String,
    val group: String,
    val name: String,
    val quantity: Int = 1,
    val specification: String = "",
    val location: String = "",
    val from: Long = 0,
    val until: Long = 0,
    val mandatory: Boolean = true,
    val substitutions: String = "",
    val dependencies: List<String> = emptyList(),
    val notes: String = "",
    val riderRef: String = "",
    val supply: String = "",
    val coverage: Coverage = Coverage.Missing,
    val commitment: Commitment = Commitment.None,
    val verified: Boolean = false,
) {
    val promised: Boolean get() = commitment == Commitment.Confirmed
    val ready: Boolean get() = coverage == Coverage.Covered && verified
    val summary: String get() = when {
        coverage == Coverage.Conflict -> "Needs a decision"
        ready -> "Checked and ready"
        commitment == Commitment.Confirmed -> "Committed · check still needed"
        commitment == Commitment.Proposed -> "Awaiting approval"
        commitment == Commitment.Declined -> "Declined · still missing"
        supply.isNotEmpty() -> "In the plan · check still needed"
        else -> "Missing: $name"
    }
}

data class Resource(
    val id: String,
    val name: String,
    val department: String,
    val specification: String,
    val source: String,
    val quantity: Int,
    val notes: String = "",
)

data class Allocation(val id: String, val resourceId: String, val act: String, val from: Long, val until: Long, val quantity: Int, val agreed: Boolean = false)

data class FulfilmentItem(
    val id: String,
    val requirementId: String,
    val kind: String,
    val provider: String,
    val title: String,
    val owner: String,
    val cost: Money?,
    val deadline: Long,
    val state: String,
    val reference: String = "",
    val detail: String = "",
)

data class Link(val requirementId: String, val kind: String, val provider: String, val proposalId: String, val reference: String, val state: String, val detail: String)

class ShowState(
    val title: String,
    val venue: String,
    val address: String,
    val date: LocalDate,
    var requirements: List<Requirement>,
    var resources: List<Resource>,
    var allocations: List<Allocation>,
    var items: List<FulfilmentItem>,
    var links: List<Link>,
) {
    fun midnight(): Long = date.atStartOfDay(SHOW_ZONE).toInstant().toEpochMilli()
    fun at(hour: Int, minute: Int = 0): Long = midnight() + (hour * 60L + minute) * 60_000
    fun label(epochMillis: Long): String = dev.deal.shell.timeLabel(epochMillis, SHOW_ZONE)
    fun day(epochMillis: Long): String = dev.deal.shell.dateLabel(Instant.ofEpochMilli(epochMillis).atZone(SHOW_ZONE).toLocalDate())
    fun acts(): List<String> = requirements.map { it.act }.distinct().sorted()
    fun groups(act: String): List<String> = requirements.filter { it.act == act }.map { it.group }.distinct()
    fun groupCovered(act: String, group: String): Boolean =
        requirements.filter { it.act == act && it.group == group }.all { it.coverage == Coverage.Covered }
    fun coverage(requirement: Requirement): Coverage = when {
        requirement.dependencies.isNotEmpty() && requirement.dependencies.any { dep -> requirements.firstOrNull { it.id == dep }?.coverage != Coverage.Covered } -> Coverage.Missing
        else -> requirement.coverage
    }
    fun conflicts(): List<Allocation> {
        val overlapping = mutableSetOf<String>()
        allocations.groupBy { it.resourceId }.values.forEach { group ->
            group.forEachIndexed { i, a -> group.drop(i + 1).forEach { b -> if (a.from < b.until && b.from < a.until) { overlapping += a.id; overlapping += b.id } } }
        }
        return allocations.filter { it.id in overlapping }
    }
    fun requirement(id: String): Requirement? = requirements.firstOrNull { it.id == id }
    fun resource(id: String): Resource? = resources.firstOrNull { it.id == id }
    fun update(id: String, transform: (Requirement) -> Requirement): ShowState {
        requirements = requirements.map { if (it.id == id) transform(it) else it }
        return this
    }
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("venue", venue).put("address", address).put("date", date.toString())
        .put("requirements", JSONArray(requirements.map { req -> JSONObject()
            .put("id", req.id).put("act", req.act).put("department", req.department).put("group", req.group).put("name", req.name)
            .put("quantity", req.quantity).put("specification", req.specification).put("location", req.location)
            .put("from", req.from).put("until", req.until).put("mandatory", req.mandatory).put("substitutions", req.substitutions)
            .put("dependencies", JSONArray(req.dependencies)).put("notes", req.notes).put("riderRef", req.riderRef)
            .put("supply", req.supply).put("coverage", req.coverage.name).put("commitment", req.commitment.name).put("verified", req.verified) }))
        .put("resources", JSONArray(resources.map { JSONObject().put("id", it.id).put("name", it.name).put("department", it.department)
            .put("specification", it.specification).put("source", it.source).put("quantity", it.quantity).put("notes", it.notes) }))
        .put("allocations", JSONArray(allocations.map { JSONObject().put("id", it.id).put("resourceId", it.resourceId).put("act", it.act)
            .put("from", it.from).put("until", it.until).put("quantity", it.quantity).put("agreed", it.agreed) }))
        .put("items", JSONArray(items.map { JSONObject().put("id", it.id).put("requirementId", it.requirementId).put("kind", it.kind)
            .put("provider", it.provider).put("title", it.title).put("owner", it.owner).put("cost", it.cost?.cents ?: JSONObject.NULL)
            .put("deadline", it.deadline).put("state", it.state).put("reference", it.reference).put("detail", it.detail) }))
        .put("links", JSONArray(links.map { JSONObject().put("requirementId", it.requirementId).put("kind", it.kind).put("provider", it.provider)
            .put("proposalId", it.proposalId).put("reference", it.reference).put("state", it.state).put("detail", it.detail) }))

    companion object {
        fun parse(text: String): ShowState {
            val json = JSONObject(text)
            fun array(key: String) = json.optJSONArray(key) ?: JSONArray()
            return ShowState(
                json.getString("title"), json.getString("venue"), json.getString("address"), LocalDate.parse(json.getString("date")),
                (0 until array("requirements").length()).map { i -> val o = array("requirements").getJSONObject(i)
                    Requirement(o.getString("id"), o.getString("act"), o.getString("department"), o.getString("group"), o.getString("name"),
                        o.optInt("quantity", 1), o.optString("specification"), o.optString("location"), o.optLong("from"), o.optLong("until"),
                        o.optBoolean("mandatory", true), o.optString("substitutions"),
                        (0 until o.optJSONArray("dependencies").let { it?.length() ?: 0 }).map { o.getJSONArray("dependencies").getString(it) },
                        o.optString("notes"), o.optString("riderRef"), o.optString("supply"),
                        Coverage.valueOf(o.optString("coverage", "Missing")), Commitment.valueOf(o.optString("commitment", "None")), o.optBoolean("verified")) },
                (0 until array("resources").length()).map { i -> val o = array("resources").getJSONObject(i)
                    Resource(o.getString("id"), o.getString("name"), o.getString("department"), o.optString("specification"), o.optString("source"), o.optInt("quantity", 1), o.optString("notes")) },
                (0 until array("allocations").length()).map { i -> val o = array("allocations").getJSONObject(i)
                    Allocation(o.getString("id"), o.getString("resourceId"), o.getString("act"), o.optLong("from"), o.optLong("until"), o.optInt("quantity", 1), o.optBoolean("agreed")) },
                (0 until array("items").length()).map { i -> val o = array("items").getJSONObject(i)
                    FulfilmentItem(o.getString("id"), o.getString("requirementId"), o.optString("kind"), o.optString("provider"), o.getString("title"),
                        o.optString("owner"), if (o.isNull("cost")) null else Money(o.getInt("cost")), o.optLong("deadline"), o.optString("state"), o.optString("reference"), o.optString("detail")) },
                (0 until array("links").length()).map { i -> val o = array("links").getJSONObject(i)
                    Link(o.getString("requirementId"), o.optString("kind"), o.optString("provider"), o.optString("proposalId"), o.optString("reference"), o.optString("state"), o.optString("detail")) },
            )
        }

        /** The agreed fixture: one show, two acts, three identifiable gaps. */
        fun fixture(date: LocalDate): ShowState {
            fun id(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"
            val vocalAct = "Static Bloom"
            val other = "Glass Harbour"
            val amp = id("amp"); val mic = id("mic"); val micName = id("micname"); val input = id("input"); val cable = id("cable")
            val ampOther = id("amp2"); val cymbals = id("cymbals"); val pizza = id("pizza")
            val base = ShowState("Friday at The Foundry", "The Foundry", "12 Foundry Lane, Berlin", date, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            fun t(hour: Int, minute: Int = 0) = base.at(hour, minute)
            val requirements = listOf(
                Requirement(micName, vocalAct, "Sound", "Lead vocal setup", "Microphone", specification = "Venue condenser, XLR output", location = "Stage centre", from = t(16, 15), until = t(22, 30), supply = "Venue microphone", coverage = Coverage.Covered, riderRef = "SB rider · Technical · vocal"),
                Requirement(mic, vocalAct, "Sound", "Lead vocal setup", "Boom stand", specification = "Adjustable boom with clip adapter", location = "Stage centre", from = t(16, 15), until = t(22, 30), substitutions = "Equivalent boom stand", riderRef = "SB rider · Technical · vocal"),
                Requirement(cable, vocalAct, "Sound", "Lead vocal setup", "Compatible cable", specification = "Balanced XLR female → XLR male, minimum 10 m", location = "Stage centre", from = t(16, 15), until = t(22, 30), substitutions = "Longer cable of the same connectors", riderRef = "SB rider · Technical · vocal"),
                Requirement(input, vocalAct, "Sound", "Lead vocal setup", "Mixer input", specification = "Channel 1, phantom power available", location = "Front of house", from = t(16, 15), until = t(22, 30), supply = "Allocated venue input", coverage = Coverage.Covered, riderRef = "SB rider · Technical · vocal"),
                Requirement(amp, vocalAct, "Backline", "Bass preparation", "Bass amplifier", specification = "At least 300 W, 6.35 mm input, balanced XLR DI output", location = "Backstage", from = t(16, 0), until = t(17, 0), supply = "Venue amplifier", coverage = Coverage.Conflict, riderRef = "SB rider · Technical · backline"),
                Requirement(ampOther, other, "Backline", "Bass preparation", "Bass amplifier", specification = "At least 300 W, 6.35 mm input, balanced XLR DI output", location = "Backstage", from = t(15, 30), until = t(16, 30), supply = "Venue amplifier", coverage = Coverage.Conflict, riderRef = "GH rider · Technical · backline"),
                Requirement(cymbals, other, "Backline", "Drum riser", "Cymbal stands", specification = "Two stands, boom compatible", location = "Stage right", from = t(15, 30), until = t(22, 30), supply = "Participant contribution", coverage = Coverage.Covered, riderRef = "GH rider · Technical · drums"),
                Requirement(pizza, vocalAct, "Hospitality", "Dressing room", "Dressing-room pizza", quantity = 6, specification = "Six pizzas, at least two vegan", location = "Static Bloom dressing room", from = t(17, 30), until = t(18, 30), mandatory = false, substitutions = "Any suitable menu item for non-vegan portions", riderRef = "SB rider · Hospitality · food"),
            )
            val resources = listOf(
                Resource(id("micres"), "Venue condenser microphone", "Sound", "XLR output, phantom powered", "Venue", 2),
                Resource(id("input"), "Mixer channel 1", "Sound", "XLR input with phantom power", "Venue", 1),
                Resource(amp + "-res", "Venue bass amplifier", "Backline", "300 W, 6.35 mm input, balanced XLR DI", "Venue", 1, "Shared between both acts"),
                Resource(id("stands"), "Venue cymbal stands", "Backline", "Boom compatible", "Participant", 2),
                Resource(id("catering"), "Venue catering budget", "Hospitality", "Applies to dressing-room food", "Venue", 1),
            )
            val allocations = listOf(
                Allocation(id("alloc"), amp + "-res", other, t(15, 30), t(16, 30), 1),
                Allocation(id("alloc"), amp + "-res", vocalAct, t(16, 0), t(17, 0), 1),
                Allocation(id("alloc"), micName + "-res", vocalAct, t(16, 15), t(22, 30), 1),
                Allocation(id("alloc"), id("input"), vocalAct, t(16, 15), t(22, 30), 1),
                Allocation(id("alloc"), id("stands"), other, t(15, 30), t(22, 30), 2, agreed = true),
            )
            return ShowState(base.title, base.venue, base.address, date, requirements, resources, allocations, emptyList(), emptyList())
        }
    }
}

/** A readable deadline for intent text; the stored instant stays authoritative. */
fun Requirement.end(withDay: Boolean = false): String =
    if (withDay) dev.deal.shell.dateTimeLabel(until, SHOW_ZONE) else dev.deal.shell.timeLabel(until, SHOW_ZONE)
