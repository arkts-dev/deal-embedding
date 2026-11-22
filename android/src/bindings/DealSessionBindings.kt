package dev.deal.embedding

import android.content.Context
import org.json.JSONObject
import java.io.File
import dev.deal.embedding.capabilities.CapabilityBindings

/** DEAL JS loading and Deal UI ABI; independent of sandbox service lifetime. */
internal object DealSessionBindings {
    fun bundle(context: Context, config: RuntimeConfig, output: File, restored: String?): String {
        val manifest = JSONObject(File(output,"admission.json").readText())
        require(CompileInput.target(config.target) != null && manifest.optString("target") == config.target) {
            "ADMISSION_TARGET: compiled target differs from host configuration"
        }
        return SandboxBundle.build(
            context, listOf("dealui-session.js", "ui-session.js"), CapabilityBindings.script(config.contracts),
            "mountDealUi(factories,\"$ENTRY_NAME.js\",${record(restored)},${appModule(config)?.let { JSONObject.quote(it) } ?: "null"})", output = output)
    }
    /** The compiled app module owns the issued persistence roles; only retaining hosts name it. */
    fun appModule(config: RuntimeConfig): String? = if (config.retainState) "${config.sourceName}.js" else null
    /** Saved bytes stay data: pass them as a quoted literal parsed inside the isolate. */
    fun record(saved: String?): String = saved?.let { "dealPersisted(${JSONObject.quote(it)})" } ?: "null"
    const val checkpoint = "persistDealUi()"
    const val snapshot = "dealUi.snapshot()"
    const val requests = "dealCapabilities.take()"
    const val dispose = "if (globalThis.dealUi) dealUi.dispose(); ''"
    fun dispatch(slot: Int, payload: String?) = "dealUi.dispatch($slot, ${payload?.let { JSONObject.quote(it) } ?: "null"})"
}
