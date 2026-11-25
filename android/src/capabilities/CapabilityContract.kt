package dev.deal.embedding.capabilities

import dev.deal.embedding.*
import org.json.JSONArray

/** Contract registration; execution is framework-internal. */
class NativeCapabilities private constructor(contract: CapabilityContract, handlers: Map<String, (JSONArray, InvocationContext) -> Any>, classification: Map<String, String>, marker: Boolean) {
    private val frozen = contract.also { it.validate() }.let { CapabilityContract.parse(it.json().toString()) }
    val contract: CapabilityContract get() = CapabilityContract.parse(frozen.json().toString())
    private val handlers = handlers.toMap()
    /** The host's read or mutating classification of each disclosed function: host disclosure, never a
     * default inside compiled policy. An unclassified function is treated as mutating, so a provider
     * that classifies nothing authorizes no read. */
    private val classification = classification.toMap()
    fun classification(): Map<String, String> = classification
    /**
     * The contract text this registration discloses, carrying the host's per-function read or mutating
     * class. Discovery is the only path a separately installed provider's contract reaches the
     * consuming app, so the class travels with it; a function the registration does not classify is
     * disclosed as unclassified and the consuming boundary treats it as mutating.
     */
    internal fun disclosed(): org.json.JSONObject {
        val json = contract.json()
        val functions = json.getJSONArray("functions")
        for (index in 0 until functions.length()) {
            val entry = functions.getJSONObject(index)
            val stated = classification[entry.getString("name")] ?: ""
            entry.put("classification", if (stated.isNotEmpty()) stated else entry.optString("classification", ""))
        }
        return json
    }
    internal fun declaration(): String = disclosed().toString()
    constructor(contract: CapabilityContract, handlers: Map<String, (JSONArray) -> Any>, classification: Map<String, String> = emptyMap()) : this(contract,handlers.mapValues { (_, handler) -> { args, _ -> handler(args) } },classification,true)
    init { contract.validate(); require(handlers.keys == contract.functions.map { it.name }.toSet()); for ((name, kind) in classification) require(name in handlers.keys && kind in setOf("read", "mutating")) { "Invalid host classification: $name" } }
    internal companion object {
        fun contextual(contract: CapabilityContract, handlers: Map<String, (JSONArray, InvocationContext) -> Any>, classification: Map<String, String> = emptyMap()) = NativeCapabilities(contract,handlers,classification,true)
    }
    internal fun invoke(name: String, args: JSONArray, invocation: InvocationContext = InvocationContext(null)): Any {
        val function = frozen.functions.single { it.name == name }
        function.validateArguments(args)
        return handlers.getValue(name)(args,invocation).also { function.result.validate(it) }
    }
}
