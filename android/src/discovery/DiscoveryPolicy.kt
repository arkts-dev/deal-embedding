package dev.deal.embedding.discovery

import android.content.Context
import dev.deal.embedding.*
import org.json.JSONArray
import org.json.JSONObject

/** The compiled handoff plan: title, compiled intent, compiled schema and captured context bytes. */
internal class HandoffPlan internal constructor(value: JSONObject) {
    val title: String = value.getString("title")
    val intent: String = value.getString("intent")
    // Wire data, not a re-validated schema: the one context validator owns every schema bound.
    val schema: JSONObject = value.getJSONObject("schema")
    val context: String = value.getString("context")
}

/** Synchronous bridge to pure compiled framework policy; no provider or generation authority. */
internal class DiscoveryPolicy(context: Context) : AutoCloseable {
    private val engine = SandboxEngine.acquire(context)
    private val program = SandboxProgram(engine, 256 * 1024)
    init {
        try {
            program.evaluate(SandboxBundle.build(context, emptyList(), "",
                "(() => { globalThis.discoveryPolicy = dealLoad(factories,'discovery.js').entry; return ''; })()", assetRoot = "discovery"))
        } catch (error: Throwable) {
            runCatching { teardown({ program.close() }, { SandboxEngine.release(engine) }) }.onFailure(error::addSuppressed)
            throw error
        }
    }
    private fun capture(input: CapturedDiscoveryFacts) = JSONObject().put("facts", JSONArray(input.factsJson))
        .put("capabilityIds", JSONArray(input.capabilityIds.sorted())).put("catalogue", input.catalogueJson).toString()
    @Synchronized private fun call(name: String, vararg arguments: String): String = program.evaluateString(
        "discoveryPolicy[${JSONObject.quote(name)}].\$f(...${JSONArray(arguments.toList())})")
    /** Decision errors keep their compiled code across the isolate boundary; genuine faults stay faults. */
    @Synchronized private fun envelope(name: String, vararg arguments: String): String {
        val outcome = program.evaluateString("(() => { try { return JSON.stringify({ok:true,value: discoveryPolicy[${JSONObject.quote(name)}].\$f(...${JSONArray(arguments.toList())})}); } catch (error) { if (!(error && error.\$classname === 'Error')) { throw error; } return JSON.stringify({ok:false,code:error.name || 'FAILED',message:error.message || 'Rejected by compiled discovery policy'}); } })()")
        val result = JSONObject(outcome)
        if (result.getBoolean("ok")) return result.getString("value")
        throw ProtocolFailure(result.getString("code"), result.getString("message"))
    }
    private fun decision(name: String, vararg arguments: String) { envelope(name, *arguments) }
    fun prompt(input: CapturedDiscoveryFacts) = call("prompt", capture(input))
    fun parse(text: String, input: CapturedDiscoveryFacts): DiscoverySuggestion? {
        require(text.toByteArray(Charsets.UTF_8).size <= 8192) { "Discovery reply too large" }
        val result = JSONObject(call("validate", text, capture(input)))
        require(result.getBoolean("accepted")) { result.getString("message") }
        if (result.isNull("suggestion")) return null
        val value = result.getJSONObject("suggestion")
        fun refs(key: String) = value.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        return DiscoverySuggestion(value.getString("title"), value.getString("brief"), refs("supportingFactIds"), refs("capabilityIds"), input)
    }
    /** Acceptance assembles the Request in compiled policy: one title, the compiled intent, the facts. */
    fun handoff(suggestionJson: String, input: CapturedDiscoveryFacts): HandoffPlan =
        HandoffPlan(JSONObject(envelope("handoff", suggestionJson, capture(input))))
    fun admit(input: CapturedDiscoveryFacts, fresh: CapturedDiscoveryFacts, issued: Boolean) {
        decision("admit",input.evidence.identity,fresh.evidence.identity,input.catalogueJson,fresh.catalogueJson,issued.toString())
    }
    override fun close() = teardown({ program.close() }, { SandboxEngine.release(engine) })
}
