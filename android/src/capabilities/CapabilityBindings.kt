package dev.deal.embedding.capabilities

import dev.deal.embedding.*
import org.json.JSONArray

/** One contract drives checker declarations and generic sandbox bindings. No executable provider source. */
internal object CapabilityBindings {
    private fun type(t: WireType): String = when(t.kind) { "record" -> "Record<string, unknown>"; "array" -> type(t.element!!) + "[]"; else -> t.kind }
    fun declaration(c: CapabilityContract): String = c.functions.joinToString("\n") { f -> "export function ${f.name}(${f.parameters.mapIndexed { index, parameter -> "arg$index: ${type(parameter.type)}" }.joinToString(", ")}): Promise<${type(f.result)}>;" }
    /** The generation host's declarations: one labelled comment line plus each function declaration. */
    fun declarations(contracts: List<CapabilityContract>): String = contracts.joinToString("\n") { contract -> "// ${contract.module}\n${declaration(contract)}" }
    fun script(contracts: List<CapabilityContract>): String = "\nconfigureDealCapabilities(${JSONArray(contracts.map { it.json() })});\n"
}
