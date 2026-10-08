package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import dev.deal.embedding.capabilities.CapabilityHost
import java.io.File

/** One live isolate and compiler-owned store, serialized by the activity worker. */
internal class UiSession(private val context: Context, private val broker: CapabilityHost, private val engine: JavaScriptSandbox, private val config: EmbeddingConfig) : AutoCloseable {
    private val program = SandboxProgram(engine, 256 * 1024)
    private var capabilitySession: dev.deal.embedding.capabilities.CapabilitySession? = null
    private var closed = false
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
    fun dispatch(slot: Int, payload: String?) { evaluate(DealSessionBindings.dispatch(slot, payload)) }
    fun poll(): JSONObject {
        val encoded = evaluate(DealSessionBindings.requests)
        check(encoded.toByteArray().size <= 16 * 1024) { "Capability request batch too large" }
        val requests = JSONArray(encoded)
        check(requests.length() <= 8) { "Capability request batch too large: " + requests.length() + " pending" }
        val now = android.os.SystemClock.elapsedRealtime()
        val capabilities = checkNotNull(capabilitySession) { "UI session is not mounted" }
        for (i in 0 until requests.length()) capabilities.receive(requests.getJSONObject(i), now)
        val replies = capabilities.drain(now)
        if (replies.isNotEmpty()) evaluate("dealCapabilities.deliver(${JSONArray(replies)})")
        return JSONObject(evaluate(DealSessionBindings.snapshot))
    }
    override fun close() {
        if (!closed) {
            closed = true
            try { program.evaluate(DealSessionBindings.dispose) } catch (_: Exception) { /* Terminated isolates cannot be disposed. */ }
            finally { try { program.close() } finally { capabilitySession?.close(); capabilitySession = null } }
        }
    }
}

