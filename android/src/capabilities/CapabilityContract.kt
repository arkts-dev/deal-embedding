package dev.deal.embedding.capabilities

import org.json.*

/** Bounded, versioned wire types. Records become DEAL tables, never host objects. */
data class CapabilityType(
    val kind: String,
    val fields: Map<String, CapabilityType> = emptyMap(),
    val element: CapabilityType? = null,
    val maximum: Int = 128,
    val minimumInt: Int = Int.MIN_VALUE,
    val maximumInt: Int = Int.MAX_VALUE,
) {
    init {
        require(kind in setOf("int", "string", "boolean", "null", "record", "array"))
        require(maximum in 0..8192 && minimumInt <= maximumInt)
        require((kind == "array") == (element != null))
        require((kind == "record") == fields.isNotEmpty())
        require(fields.size <= 32 && fields.keys.all { identifier(it) })
    }
    fun validate(value: Any?, depth: Int = 0) {
        require(depth <= 8)
        when (kind) {
            "null" -> require(value == null || value === JSONObject.NULL)
            "int" -> require(value is Int && value in minimumInt..maximumInt)
            "string" -> require(value is String && value.length <= maximum)
            "boolean" -> require(value is Boolean)
            "record" -> {
                require(value is JSONObject && value.length() == fields.size)
                fields.forEach { (name, type) -> require(value.has(name)); type.validate(value.get(name), depth + 1) }
            }
            "array" -> { require(value is JSONArray && value.length() <= maximum); for (i in 0 until value.length()) element!!.validate(value.get(i), depth + 1) }
        }
    }
    fun json(): JSONObject = JSONObject().put("kind", kind).put("maximum", maximum).put("minimumInt", minimumInt).put("maximumInt", maximumInt).also { output ->
        if (element != null) output.put("element", element.json())
        if (fields.isNotEmpty()) output.put("fields", JSONObject().also { f -> fields.forEach { (name, type) -> f.put(name, type.json()) } })
    }
    companion object {
        val IntType = CapabilityType("int")
        val NullType = CapabilityType("null")
        fun identifier(value: String) = value.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))
        fun parse(json: JSONObject, depth: Int = 0): CapabilityType {
            require(depth <= 8 && json.length() <= 6)
            val fields = json.optJSONObject("fields")
            return CapabilityType(json.getString("kind"), fields?.keys()?.asSequence()?.associateWith { parse(fields.getJSONObject(it), depth + 1) } ?: emptyMap(),
                json.optJSONObject("element")?.let { parse(it, depth + 1) }, json.getInt("maximum"), json.getInt("minimumInt"), json.getInt("maximumInt"))
        }
    }
}
data class CapabilityParameter(val name: String, val type: CapabilityType)
data class CapabilityFunction(val name: String, val description: String, val parameters: List<CapabilityParameter>, val result: CapabilityType) {
    init { require(CapabilityType.identifier(name) && description.length in 1..512 && parameters.size <= 8 && parameters.map { it.name }.distinct().size == parameters.size && parameters.all { CapabilityType.identifier(it.name) }) }
    fun validateArguments(args: JSONArray) { require(args.length() == parameters.size); parameters.forEachIndexed { index, p -> p.type.validate(args.get(index)) } }
    fun json() = JSONObject().put("name", name).put("description", description).put("parameters", JSONArray(parameters.map { JSONObject().put("name", it.name).put("type", it.type.json()) })).put("result", result.json())
}
data class CapabilityContract(val module: String, val description: String, val functions: List<CapabilityFunction>, val version: Int = 1) {
    init {
        require(version == 1 && module.matches(Regex("host/[a-z][a-z0-9-]{0,63}")) && description.length in 1..512)
        require(functions.size in 1..32 && functions.map { it.name }.distinct().size == functions.size)
    }
    fun json() = JSONObject().put("version", version).put("module", module).put("description", description).put("functions", JSONArray(functions.map { it.json() }))
    companion object {
        fun parse(text: String): CapabilityContract {
            require(text.toByteArray().size <= 16 * 1024)
            val json = JSONObject(text); require(json.length() == 4)
            val functions = json.getJSONArray("functions")
            require(functions.length() in 1..32)
            return CapabilityContract(json.getString("module"), json.getString("description"), (0 until functions.length()).map { i ->
                val f = functions.getJSONObject(i); val parameters = f.getJSONArray("parameters")
                require(parameters.length() <= 8)
                CapabilityFunction(f.getString("name"), f.getString("description"), (0 until parameters.length()).map { j ->
                    val p = parameters.getJSONObject(j); CapabilityParameter(p.getString("name"), CapabilityType.parse(p.getJSONObject("type")))
                }, CapabilityType.parse(f.getJSONObject("result")))
            }, json.getInt("version"))
        }
    }
}
/** Trusted native code registers handlers against the same contract used for checking and binding. */
class NativeCapabilities(val contract: CapabilityContract, val handlers: Map<String, (JSONArray) -> Any>) {
    init { require(handlers.keys == contract.functions.map { it.name }.toSet()) }
    fun invoke(name: String, args: JSONArray): Any {
        val function = contract.functions.single { it.name == name }
        function.validateArguments(args)
        return handlers.getValue(name)(args).also { function.result.validate(it) }
    }
}
