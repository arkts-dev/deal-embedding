package dev.deal.embedding.capabilities

import dev.deal.embedding.*
import org.json.JSONArray

/** Contract registration; execution is framework-internal. */
class NativeCapabilities private constructor(contract: CapabilityContract, handlers: Map<String, (JSONArray, InvocationContext) -> Any>, marker: Boolean) {
    private val frozen = contract.also { it.validateSchema() }.let { CapabilityContract.parse(it.json().toString()) }
    val contract: CapabilityContract get() = CapabilityContract.parse(frozen.json().toString())
    private val handlers = handlers.toMap()
    constructor(contract: CapabilityContract, handlers: Map<String, (JSONArray) -> Any>) : this(contract,handlers.mapValues { (_, handler) -> { args, _ -> handler(args) } },true)
    init { contract.validateSchema(); require(handlers.keys == contract.functions.map { it.name }.toSet()) }
    internal companion object {
        fun contextual(contract: CapabilityContract, handlers: Map<String, (JSONArray, InvocationContext) -> Any>) = NativeCapabilities(contract,handlers,true)
    }
    internal fun invoke(name: String, args: JSONArray, invocation: InvocationContext = InvocationContext(null)): Any {
        val function = frozen.functions.single { it.name == name }
        function.validateArguments(args)
        return handlers.getValue(name)(args,invocation).also { function.result.validate(it) }
    }
}
