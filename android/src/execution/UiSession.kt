package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import dev.deal.embedding.capabilities.CapabilityHost
import java.io.File

/** One live isolate and compiler-owned store, serialized by the activity worker. */
internal class UiSession(private val context: Context, private val broker: CapabilityHost, private val engine: JavaScriptSandbox, private val config: EmbeddingConfig,
    private val receipts: StageReceipts = StageReceipts(context), private val candidate: String = "", private val workspace: String = "") : AutoCloseable {
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
    fun state(): String = evaluate(DealSessionBindings.state)
    fun mount(output: File, restored: String? = null): JSONObject {
        check(!closed && capabilitySession == null) { "UI session is closed or already mounted" }
        val bundle = DealSessionBindings.bundle(context, config, output, restored)
        check(bundle.toByteArray().size <= 2 * 1024 * 1024) { "UI bundle too large" }
        capabilitySession = broker.openSession()
        try { return JSONObject(evaluate(bundle)) }
        catch (error: Throwable) { close(); throw error }
    }
    fun dispatch(slot: Int, payload: String?) {
        actionSequence++
        lastAction = "$actionSequence:$slot"
        receipts.stage("action", candidate, workspace, lastAction) { evaluate(DealSessionBindings.dispatch(slot, payload)) }
    }
    fun poll(): JSONObject {
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
            receipts.event("capability", "started", candidate, workspace, id.toString(), parentOperation = lastAction)
            try { capabilities.receive(request, now) }
            catch (error: Throwable) { receipts.event("capability", "failed", candidate, workspace, id.toString(), error.javaClass.simpleName); throw error }
        }
        val replies = capabilities.drain(now)
        for (reply in replies) {
            val id = reply.getInt("id")
            val started = requestStarts.remove(id) ?: now
            receipts.event("capability", if (reply.getBoolean("ok")) "completed" else "failed", candidate, workspace, id.toString(),
                reply.optJSONObject("error")?.optString("code") ?: "", now - started, parentOperation = requestParents.remove(id) ?: "")
        }
        if (replies.isNotEmpty()) receipts.stage("effect-delivery", candidate, workspace, replies.joinToString(",") { it.getInt("id").toString() }) { evaluate("dealCapabilities.deliver(${JSONArray(replies)})") }
        val snapshot = JSONObject(evaluate(DealSessionBindings.snapshot))
        val fault = snapshot.optString("fault").isNotEmpty()
        if (snapshot.getInt("version") != lastVersion || fault != lastFault) {
            lastVersion = snapshot.getInt("version"); lastFault = fault
            receipts.event("snapshot", if (fault) "fault" else "produced", candidate, workspace, version = lastVersion, parentOperation = lastAction)
        }
        return snapshot
    }
    fun published(version: Int) { receipts.event("snapshot", "published", candidate, workspace, version = version) }
    override fun close() {
        if (!closed) {
            closed = true
            requestStarts.keys.forEach { receipts.event("capability", "cancelled", candidate, workspace, it.toString()) }
            requestStarts.clear(); requestParents.clear()
            try { program.evaluate(DealSessionBindings.dispose) } catch (_: Exception) { /* Terminated isolates cannot be disposed. */ }
            finally { try { program.close() } finally { capabilitySession?.close(); capabilitySession = null } }
        }
    }
}

