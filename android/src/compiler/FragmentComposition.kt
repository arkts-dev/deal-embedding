package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** JSON transport only; portable DEAL binds compiler-parsed authored view trees. */
internal class FragmentComposition(private val contracts: List<CapabilityContract>) {
    fun expand(input: JSONObject): JSONObject = try {
        val catalogue = JSONObject().put("contracts", JSONArray(contracts.map { it.json() }))
        JSONObject(CompositionBridge.expand(input.toString(), catalogue.toString()))
    } catch (error: deal.codegen.jvm.JvmRuntime.DealError) {
        if (error.code !in setOf("INVALID_CONFIG", "INVALID_FORMULA", "E8001", "E8009")) throw error
        throw BehaviorCandidate.Rejected("Fragments: ${error.msg}")
    }
}
