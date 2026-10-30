package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Data transport to checked portable lowering; no native behavioral emission. */
internal class BehaviorCandidate(
    private val contracts: List<CapabilityContract>,
    private val allowCustom: Boolean = false,
    private val selectionPrototype: Boolean = false,
    private val timing: (String, Long) -> Unit = { _, _ -> },
) {
    class Rejected(message: String) : IllegalArgumentException(message)

    fun assemble(input: JSONObject): JSONObject {
        val fragments = input.has("fragments")
        try {
            val plan = if (fragments) FragmentComposition(contracts, timing).expand(input) else input
            val catalogue = JSONObject().put("contracts", JSONArray(contracts.map { it.json() }))
            val started = System.nanoTime()
            val source = CompositionBridge.lower(plan.toString(), catalogue.toString(), fragments, allowCustom, selectionPrototype)
            timing("lower", System.nanoTime()-started)
            return JSONObject(source)
        } catch (error: deal.codegen.jvm.JvmRuntime.DealError) {
            if (error.code !in setOf("INVALID_CONFIG", "INVALID_FORMULA", "INVALID_JSON", "E8001", "E8009")) throw error
            throw Rejected(error.msg)
        } catch (error: JSONException) {
            throw Rejected("Malformed behavioral plan: ${error.message}")
        }
    }
}
