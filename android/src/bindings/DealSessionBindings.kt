package dev.deal.embedding

import android.content.Context
import org.json.JSONObject
import java.io.File
import dev.deal.embedding.capabilities.CapabilityBindings

/** DEAL JS loading and Deal UI ABI; independent of sandbox service lifetime. */
internal object DealSessionBindings {
    /** The compiled pair's issued composition metadata; absent for a supplied source. */
    private fun composition(output: File): JSONObject? {
        val manifest = JSONObject(File(output, "admission.json").readText())
        return manifest.optJSONObject("composition")
            ?: manifest.optString("composition").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    /** The private Session drivers this composed pair issues: each is scheduled only when it exists. */
    fun driven(output: File): Set<String> {
        val parts = composition(output) ?: return emptySet()
        val names = linkedSetOf<String>()
        if ((parts.optJSONArray("triggers")?.length() ?: 0) > 0) names.add("triggers")
        if ((parts.optJSONArray("activation")?.length() ?: 0) > 0) names.add("activation")
        if ((parts.optJSONObject("expiry")?.optJSONArray("deadlines")?.length() ?: 0) > 0) names.add("expiry")
        return names
    }
    fun bundle(context: Context, config: RuntimeConfig, output: File, restored: String?): String {
        val manifest = JSONObject(File(output,"admission.json").readText())
        require(CompileInput.target(config.target) != null && manifest.optString("target") == config.target) {
            "ADMISSION_TARGET: compiled target differs from host configuration"
        }
        // The private clock drivers are issued composition metadata: the compiled manifest carries them
        // and the Session schedules the emitted actions they name. Nothing is derived from source text.
        val composition = composition(output)
        val drivers = composition?.optJSONArray("clockDrivers")?.toString() ?: "[]"
        // The reading carriers are issued composition metadata too: the Session binding publishes its
        // host millisecond wall reading into the compiled fields they bind, resolved from the rendered
        // tree by those issued identities.
        val readings = composition?.optJSONArray("readings")?.toString() ?: "[]"
        // The private trigger routes, authorized initial reads and expiry transition are issued
        // composition metadata as well: each names the carrier identity the Session resolves the dispatch
        // slot from and the compiled fields or queue the dispatch reads. Generated source never carries
        // them and no model string can add, rename or duplicate one.
        val triggerRoutes = composition?.optJSONArray("triggers")?.toString() ?: "[]"
        val activationRoutes = composition?.optJSONArray("activation")?.toString() ?: "[]"
        val expiryRoute = composition?.optJSONObject("expiry")?.toString() ?: "null"
        // The configured probe bounds reach the isolate once, so the probe binding enforces the host's
        // numbers instead of deciding its own.
        val probeLimits = JSONObject().put("bytes", config.execution.probeBytes).put("depth", config.execution.probeDepth)
            .put("characters", config.execution.probeCharacters).put("paths", config.execution.probePaths)
            .put("timeoutMs", config.execution.probeTimeoutMs)
        return "globalThis.dealProbeLimits = $probeLimits;" + SandboxBundle.build(
            context, listOf("dealui-session.js", "text-pattern.js", "renderer-admission.js", "ui-session.js"),
            CapabilityBindings.script(config.contracts) + RendererContract.script(context, config.target),
            "mountDealUi(factories,\"$ENTRY_NAME.js\",${record(restored)},${appModule(config)?.let { JSONObject.quote(it) } ?: "null"},null,$drivers,$readings,$triggerRoutes,$activationRoutes,$expiryRoute)", output = output)
    }
    /** The compiled app module owns the issued persistence roles; only retaining hosts name it. */
    fun appModule(config: RuntimeConfig): String? = if (config.retainState) "${config.sourceName}.js" else null
    /** Saved bytes stay data: pass them as a quoted literal parsed inside the isolate. */
    fun record(saved: String?): String = saved?.let { "dealPersisted(${JSONObject.quote(it)})" } ?: "null"
    const val checkpoint = "persistDealUi()"
    const val snapshot = "dealUi.snapshot()"
    const val requests = "dealCapabilities.take()"
    /** The probe lane of the same isolate: the public-probe mechanism, not a provider call. */
    const val probes = "dealProbe.take()"
    const val dispose = "if (globalThis.dealUi) dealUi.dispose(); ''"
    fun dispatch(slot: Int, payload: String?) = "dealUi.dispatch($slot, ${payload?.let { JSONObject.quote(it) } ?: "null"})"
    /** Paired adapter readings prepared for the admitted action; compiled policy decides their meaning. */
    fun clockPump(monotoneMs: Long, wallMs: Long, checkPress: Boolean) =
        "dealClockPump($monotoneMs, $wallMs, $checkPress)"
    /** The Session activation driver: one authorized initial read, guarded by the compiled marker. */
    fun activate(monotoneMs: Long) = "dealActivate($monotoneMs)"
    /** The Session trigger driver: the head of every ordered clock-end and entry-added queue. */
    const val triggers = "dealTriggers()"
    /** The Session expiry driver: the compiled deadline transition at the reading this lane took. */
    fun expire(monotoneMs: Long) = "dealExpire($monotoneMs)"
    /** The smallest issued driver interval for this compiled pair, or 0 when it drives no clock. */
    fun clockInterval(output: File): Long {
        val drivers = composition(output)?.optJSONArray("clockDrivers") ?: return 0
        var interval = 0L
        for (index in 0 until drivers.length()) {
            val value = drivers.optJSONObject(index)?.optLong("intervalMs", 0) ?: 0
            if (value > 0 && (interval == 0L || value < interval)) interval = value
        }
        return interval
    }
}
