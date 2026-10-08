package dev.deal.embedding.capabilities

import org.json.JSONArray

/** One contract drives checker declarations and generic sandbox bindings. No executable provider source. */
object CapabilityBindings {
    private fun type(t: CapabilityType): String = when(t.kind) { "record" -> "table"; "array" -> type(t.element!!) + "[]"; else -> t.kind }
    fun declaration(c: CapabilityContract): String = c.functions.joinToString("\n") { f -> "export async function ${f.name}(${f.parameters.mapIndexed { index, parameter -> "arg$index: ${type(parameter.type)}" }.joinToString(", ")}): ${type(f.result)};" }
    fun script(contracts: List<CapabilityContract>): String = "\nconfigureDealCapabilities(${JSONArray(contracts.map { it.json() })});\n"
}
