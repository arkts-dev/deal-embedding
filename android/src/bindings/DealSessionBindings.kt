package dev.deal.embedding

import android.content.Context
import org.json.JSONObject
import java.io.File
import dev.deal.embedding.capabilities.CapabilityBindings

/** DEAL JS loading and Deal UI ABI; independent of sandbox service lifetime. */
internal object DealSessionBindings {
    fun bundle(context: Context, config: EmbeddingConfig, output: File, restored: String?): String = SandboxBundle.build(
        context, listOf("ui-session.js"), CapabilityBindings.script(config.contracts),
        "mountDealUi(factories,\"experience.js\",${restored ?: "null"})", output = output)
    const val state = "dealUi.state()"
    const val snapshot = "dealUi.snapshot()"
    const val requests = "dealCapabilities.take()"
    const val dispose = "if (globalThis.dealUi) dealUi.dispose(); ''"
    fun dispatch(slot: Int, payload: String?) = "dealUi.dispatch($slot, ${payload?.let { JSONObject.quote(it) } ?: "null"})"
    fun staged(contract: dev.deal.embedding.capabilities.CapabilityContract) = CapabilityBindings.declaration(contract)
}
