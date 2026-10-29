package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import dev.deal.embedding.capabilities.CapabilityHost
import java.io.File

/** One live isolate and compiler-owned store, advanced by embedding. */
internal class UiSession(private val context: Context, private val broker: CapabilityHost, private val engine: JavaScriptSandbox, private val config: RuntimeConfig,
    private val log: EmbeddingLog = EmbeddingLog(context), private val candidate: String = "", private val workspace: String = "") : AutoCloseable {
    internal fun logTrace(): String = log.run
    private val program = SandboxProgram(engine, 256 * 1024)
    private var capabilitySession: dev.deal.embedding.capabilities.CapabilitySession? = null
    private var closed = false
    private var lastVersion = -1
    private var lastFault = false
    private var actionSequence = 0
    private var lastAction = ""
    private val requestParents = mutableMapOf<Int, String>()
    private val requestStarts = mutableMapOf<Int, Long>()
    private fun evaluate(source: String): String = program.evaluate(source)
    fun state(): String? = evaluate(DealSessionBindings.checkpoint).takeUnless { it == "null" }
    fun mount(output: File, restored: String? = null): JSONObject {
        check(!closed && capabilitySession == null) { "UI session is closed or already mounted" }
        val bundle = DealSessionBindings.bundle(context, config, output, restored)
        check(bundle.toByteArray().size <= 2 * 1024 * 1024) { "UI bundle too large" }
        capabilitySession = broker.openSession(log)
        log.event("session", "opened", candidate, workspace)
        try { return JSONObject(evaluate(bundle)).also { recordSnapshot(it) } }
        catch (error: Throwable) { close(); throw error }
    }
    fun dispatch(slot: Int, payload: String?) {
        actionSequence++
        lastAction = "$actionSequence:$slot"
        val span = "$workspace:action:$actionSequence"
        lastAction = span
        log.event("action", "started", candidate, workspace, span, detail = payload, span = span)
        try { evaluate(DealSessionBindings.dispatch(slot, payload)); log.event("action", "completed", candidate, workspace, span, span = span) }
        catch (error: Throwable) { log.generationTelemetry?.runtimeFailure(error.javaClass.simpleName);log.event("action", "failed", candidate, workspace, span, code = error.javaClass.simpleName, span = span); throw error }
    }
    fun advance(): JSONObject {
        val encoded = evaluate(DealSessionBindings.requests)
        check(encoded.toByteArray().size <= 16 * 1024) { "Capability request batch too large" }
        val requests = JSONArray(encoded)
        check(requests.length() <= 8) { "Capability request batch too large: " + requests.length() + " pending" }
        val now = android.os.SystemClock.elapsedRealtime()
        val capabilities = checkNotNull(capabilitySession) { "UI session is not mounted" }
        for (i in 0 until requests.length()) {
            val request = requests.getJSONObject(i)
            val id = request.getInt("id")
            requestStarts[id] = now
            requestParents[id] = lastAction
            log.event("capability", "started", candidate, workspace, id.toString(), parentOperation = lastAction, target = request.optString("module") + "." + request.optString("function"), detail = request.toString(), span = "${capabilities.identity}:$id")
            try { capabilities.receive(request, now) }
            catch (error: Throwable) { log.event("capability", "failed", candidate, workspace, id.toString(), error.javaClass.simpleName); throw error }
        }
        val replies = capabilities.drain(now)
        for (reply in replies) {
            val id = reply.getInt("id")
            val started = requestStarts.remove(id) ?: now
            log.event("capability", if (reply.getBoolean("ok")) "completed" else "failed", candidate, workspace, id.toString(),
                reply.optJSONObject("error")?.optString("code") ?: "", now - started, parentOperation = requestParents.remove(id) ?: "", detail = reply.toString(), span = "${capabilities.identity}:$id")
        }
        if (replies.isNotEmpty()) log.stage("effect-delivery", candidate, workspace, replies.joinToString(",") { it.getInt("id").toString() }) { evaluate("dealCapabilities.deliver(${JSONArray(replies)})") }
        val snapshot = JSONObject(evaluate(DealSessionBindings.snapshot))
        if (snapshot.getInt("version") != lastVersion || snapshot.optString("fault").isNotEmpty() != lastFault) recordSnapshot(snapshot)
        return snapshot
    }
    /** Optional bounded observation of production, not a second update or renderer delivery path. */
    private fun recordSnapshot(snapshot: JSONObject) {
        lastVersion = snapshot.getInt("version"); lastFault = snapshot.optString("fault").isNotEmpty()
        if(lastFault)log.generationTelemetry?.runtimeFailure("SNAPSHOT_FAULT")
        log.event("snapshot", if (lastFault) "fault" else "produced", candidate, workspace, version = lastVersion, parentOperation = lastAction,
            detail = if (log.capturesContent) runCatching { observationValue(snapshot).toString() }.getOrNull() else null)
    }
    /** Match public protocol transport: compiler nominal metadata is not snapshot data. */
    private fun observationValue(value: Any): Any = when(value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> if (!key.startsWith("$")) out.put(key, observationValue(value.get(key))) } }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(observationValue(value.get(i))) }
        else -> value
    }
    fun published(version: Int) { require(version>=0 && version<=lastVersion) { "Unproduced snapshot version" }; log.event("snapshot", "published", candidate, workspace, version = version);log.generationTelemetry?.published() }
    override fun close() {
        if (!closed) {
            closed = true
            requestStarts.keys.forEach { log.event("capability", "cancelled", candidate, workspace, it.toString()) }
            requestStarts.clear(); requestParents.clear()
            try { program.evaluate(DealSessionBindings.dispose) } catch (_: Exception) { /* Terminated isolates cannot be disposed. */ }
            finally { try { program.close() } finally { capabilitySession?.close(); capabilitySession = null } }
        }
    }
}

