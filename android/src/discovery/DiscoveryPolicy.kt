package dev.deal.embedding.discovery

import android.content.Context
import dev.deal.embedding.*
import org.json.JSONArray
import org.json.JSONObject

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
    fun intent() = call("intent")
    fun admit(input: CapturedDiscoveryFacts, fresh: CapturedDiscoveryFacts, issued: Boolean) {
        call("admit",input.evidence.identity,fresh.evidence.identity,input.catalogueJson,fresh.catalogueJson,issued.toString())
    }
    override fun close() = teardown({ program.close() }, { SandboxEngine.release(engine) })
}
