package dev.deal.embedding

import dev.deal.embedding.*
import dev.deal.embedding.capabilities.CapabilityBoundary
import org.json.*

internal fun JSONObject.requiredInteger(name: String): Int {
    val raw = get(name); require(raw is Number) { "Expected numeric $name" }
    val value = raw.toDouble()
    require(value.isFinite() && value == kotlin.math.floor(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) { "Expected integral $name" }
    return value.toInt()
}
internal val WireType.fieldTypes: Map<String, WireType> get() = fields.associate { it.name to it.type }
internal val WireType.optionalFields: Set<String> get() = fields.filter { it.optional }.map { it.name }.toSet()
/** The schema boundary of the capability validator; context acquisition reaches the same rules. */
internal fun WireType.validateSchema() = CapabilityBoundary.schema(this, "schema", "type")
internal fun WireType.validate(value: Any?, depth: Int = 0) {
    require(depth <= CapabilityBoundary.DEPTH)
    when (kind) {
        "number" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() in minimumNumber..maximumNumber)
        "string" -> require(value is String && value.length <= maximum)
        "boolean" -> require(value is Boolean)
        "null" -> require(value == null || value === JSONObject.NULL)
        "array" -> { require(value is JSONArray && value.length() <= maximum); for (i in 0 until value.length()) element!!.validate(value.get(i), depth + 1) }
        "record" -> {
            require(value is JSONObject && value.keys().asSequence().all { key -> fields.any { it.name == key } })
            fields.forEach { if (value.has(it.name)) it.type.validate(value.get(it.name), depth + 1) else require(it.optional) }
        }
        else -> error("Invalid wire type")
    }
}
internal fun WireType.json(): JSONObject = JSONObject().put("kind",kind).put("fields",JSONArray(fields.map { JSONObject().put("name",it.name).put("type",it.type.json()).put("optional",it.optional) }))
    .put("element",element?.json() ?: JSONObject.NULL).put("maximum",maximum).put("minimumNumber",minimumNumber).put("maximumNumber",maximumNumber)
internal fun WireType.Companion.parse(value: JSONObject, depth: Int = 0): WireType {
    require(depth <= CapabilityBoundary.DEPTH && value.keys().asSequence().toSet() == setOf("kind","fields","element","maximum","minimumNumber","maximumNumber"))
    val fields = value.getJSONArray("fields")
    return WireType(value.getString("kind"), (0 until fields.length()).map { i ->
        val field = fields.getJSONObject(i); require(field.keys().asSequence().toSet() == setOf("name","type","optional"))
        WireField(field.getString("name"), parse(field.getJSONObject("type"),depth + 1),field.getBoolean("optional"))
    }, value.optJSONObject("element")?.let { parse(it,depth + 1) }, value.requiredInteger("maximum"),value.getDouble("minimumNumber"),value.getDouble("maximumNumber")).also { it.validateSchema() }
}
internal fun CapabilityFunction.validateArguments(args: JSONArray) { require(args.length() == parameters.size); parameters.forEachIndexed { i, p -> p.type.validate(args.get(i)) } }
internal fun CapabilityFunction.json() = JSONObject().put("name",name).put("description",description).put("parameters",JSONArray(parameters.map { JSONObject().put("name",it.name).put("type",it.type.json()) })).put("result",result.json())
/** Registration admits a contract through the one capability boundary validator. */
internal fun CapabilityContract.validate() = CapabilityBoundary.validate(this)
internal fun CapabilityContract.json() = JSONObject().put("module",module).put("description",description).put("version",version).put("functions",JSONArray(functions.map { it.json() }))
internal fun CapabilityContract.Companion.parse(text: String): CapabilityContract {
    CapabilityBoundary.disclosed(text)
    val value = JSONObject(text); require(value.keys().asSequence().toSet() == setOf("module","description","version","functions")) { "Capability contract: unknown or missing fields" }
    val functions = value.getJSONArray("functions")
    return CapabilityContract(value.getString("module"),value.getString("description"),(0 until functions.length()).map { i ->
        val f = functions.getJSONObject(i); require(f.keys().asSequence().toSet() == setOf("name","description","parameters","result")) { "Capability function: unknown or missing fields" }
        val parameters = f.getJSONArray("parameters")
        CapabilityFunction(f.getString("name"),f.getString("description"),(0 until parameters.length()).map { j ->
            val p = parameters.getJSONObject(j); require(p.keys().asSequence().toSet() == setOf("name","type")) { "Capability parameter: unknown or missing fields" }
            Parameter(p.getString("name"),WireType.parse(p.getJSONObject("type")))
        },WireType.parse(f.getJSONObject("result")))
    },value.requiredInteger("version")).also { CapabilityBoundary.validateDecoded(it) }
}
