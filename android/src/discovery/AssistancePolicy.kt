package dev.deal.embedding.discovery

import android.content.Context
import dev.deal.embedding.*
import org.json.JSONArray
import org.json.JSONObject

/** Approved facts, enablement, retained dismissal and saved links are mechanism inputs. */
internal class AssistanceInput(
    val identity: String,
    val discovery: Boolean,
    val generation: Boolean,
    val saved: List<String>,
    val link: String,
    val dismissed: Boolean,
) {
    internal fun json() = JSONObject().put("identity", identity).put("discovery", discovery)
        .put("generation", generation).put("saved", JSONArray(saved)).put("link", link).put("dismissed", dismissed)
}

internal class AssistanceDecision internal constructor(value: JSONObject) {
    val action: String = value.getString("action")
    val experience: String = value.optString("experience")
    val token: String = value.optString("token")
    val identity: String = value.optString("identity")
    val outcome: String = value.optString("outcome")
}

/**
 * Synchronous bridge to the compiled Open/Dismiss lifecycle. It carries no decision of its own:
 * approved facts, enablement, dismissal and saved links enter, one classified decision leaves.
 */
internal class AssistancePolicy(context: Context, private val limits: ExecutionLimits = ExecutionLimits()) : AutoCloseable {
    private val engine = SandboxEngine.acquire(context)
    private val program = SandboxProgram(engine, 256 * 1024, limits)
    init {
        try {
            program.evaluate(SandboxBundle.build(context, emptyList(), "",
                "(() => { globalThis.assistanceLifecycle = dealLoad(factories,'assistance-policy.js').entry.create.\$f(); return ''; })()",
                assetRoot = "assistance-policy"))
        } catch (error: Throwable) {
            runCatching { teardown({ program.close() }, { SandboxEngine.release(engine) }) }.onFailure(error::addSuppressed)
            throw error
        }
    }
    @Synchronized private fun call(name: String, vararg arguments: String): String = program.evaluateString(
        "assistanceLifecycle[${JSONObject.quote(name)}].\$f(...${JSONArray(arguments.toList())})")
    /** A refusal keeps its compiled code across the isolate boundary; a sandbox fault stays a fault. */
    @Synchronized private fun decide(name: String, vararg arguments: String): AssistanceDecision {
        val outcome = program.evaluateString("(() => { try { return JSON.stringify({ok:true,value: assistanceLifecycle[${JSONObject.quote(name)}].\$f(...${JSONArray(arguments.toList())})}); } catch (error) { if (!(error && error.\$classname === 'Error')) { throw error; } return JSON.stringify({ok:false,code:error.name || 'FAILED',message:error.message || 'Rejected by compiled assistance policy'}); } })()")
        val result = JSONObject(outcome)
        if (!result.getBoolean("ok")) throw ProtocolFailure(result.getString("code"), result.getString("message"))
        return AssistanceDecision(JSONObject(result.getString("value")))
    }
    fun reset(): String = call("reset")
    fun abandon(token: String = ""): String = call("abandon", token)
    fun resume(input: AssistanceInput) = decide("resume", input.json().toString())
    fun suggested(token: String, suggestion: String) = call("suggested", token, suggestion)
    fun open(input: AssistanceInput) = decide("open", input.json().toString())
    fun generated(token: String, input: AssistanceInput) = decide("generated", token, input.json().toString())
    fun activated(token: String, experience: String) = decide("activated", token, experience)
    fun reused(token: String) = decide("reused", token)
    fun failed(token: String, code: String, identity: String) = decide("failed", token, code, identity)
    fun dismiss(identity: String) = decide("dismiss", identity)
    fun retry(): Boolean = call("retry") == "true"
    override fun close() = teardown({ program.close() }, { SandboxEngine.release(engine) })
}
