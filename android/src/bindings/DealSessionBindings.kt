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
            "mountDealUi(factories,\"$ENTRY_NAME.js\",${restored ?: "null"})", output = output)
    }
    const val checkpoint = "dealUi.checkpoint()"
    const val snapshot = "dealUi.snapshot()"
    const val requests = "dealCapabilities.take()"
    const val dispose = "if (globalThis.dealUi) dealUi.dispose(); ''"
    fun dispatch(slot: Int, payload: String?) = "dealUi.dispatch($slot, ${payload?.let { JSONObject.quote(it) } ?: "null"})"
}
