package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import dev.deal.embedding.capabilities.CapabilityHost
import java.io.File

/** One live isolate and compiler-owned store, advanced by embedding. */
internal class UiSession(private val context: Context, private val broker: CapabilityHost, private val engine: JavaScriptSandbox, private val config: RuntimeConfig,
    private val log: EmbeddingLog = EmbeddingLog(context), private val candidate: String = "", private val workspace: String = "", private val origin: String = "supplied") : AutoCloseable {
    internal fun logTrace(): String = log.run
    private val program = SandboxProgram(engine, 256 * 1024, config.execution)
    private var capabilitySession: dev.deal.embedding.capabilities.CapabilitySession? = null
    // Public probes are a mechanism beside the provider boundary: they own no grant, no typed
    // capability contract and no application-provider read, so they run on their own lane.
    private val probes = ProbeSession(config.execution) { closed }
    private var closed = false
    private var lastVersion = -1
    private var lastFault = false
    private var actionSequence = 0
    private var lastAction = ""
    private var clockIntervalMs = 0L
    private var nextClockPump = 0L
    // The private drivers this composed pair issues. A pair that issues none is not evaluated for them on
    // every tick, so the Session lane pays only for the mechanisms the composition carries.
    private var driven: Set<String> = emptySet()
    private val requestParents = mutableMapOf<Int, String>()
    private val requestStarts = mutableMapOf<Int, Long>()
    /** The framework storage host for this Experience; the controller's saved record is not it. */
    private val storage = ExperienceScopedStorage(context, config.storageName, owningExperience())
    // A store scoped to anything but the owning Experience would hold records that forgetting the
    // Experience never clears, so a Session without one fails closed instead of inventing a scope.
    private fun owningExperience(): String { check(workspace.isNotEmpty()) { "A live Session store needs the owning Experience" }; return workspace }
    private fun evaluate(source: String): String = program.evaluate(source)
    /** The live Session facts compiled admission re-reads before a send and before a reply are host
     * configuration installed beside the mounted Session: the Session lifetime, the permission epoch of
     * each module, the granted modules, the host classification of each disclosed function, clock trust,
     * the admission instant and the host-configured window. They are installed again at every serialized
     * transition that can send a call and again before a reply is admitted, so a grant, an epoch, a
     * lifetime or a window that moved while the call was in flight is what the reply is decided under. A
     * snapshot taken at mount could never decide that, so no caller installs these facts once. */
    private fun installSessionFacts() {
        val facts = checkNotNull(capabilitySession) { "UI session is not mounted" }.authority()
        evaluate("configureDealSession(" + facts + ")")
    }
    /** The compiled authoritative projection; the codec envelope is the only persisted state form. */
    fun persistedState(): String? = evaluate(DealSessionBindings.checkpoint).takeUnless { it == "null" }
    fun mount(output: File, restored: String? = null): JSONObject {
        check(!closed && capabilitySession == null) { "UI session is closed or already mounted" }
        val bundle = DealSessionBindings.bundle(context, config, output, restored)
        clockIntervalMs = DealSessionBindings.clockInterval(output)
        driven = DealSessionBindings.driven(output)
        nextClockPump = 0
        capabilitySession = broker.openSession(log, origin)
        log.event("session", "opened", candidate, workspace)
        try {
            val mounted = JSONObject(evaluate(bundle))
            // The Session facts compiled admission re-reads are host configuration installed before any
            // action can dispatch, so an Experience cannot run an unclassified, ungranted or
            // lifetime-less call on a placeholder.
            installSessionFacts()
            recordSnapshot(mounted)
            return mounted
        }
        catch (error: Throwable) { close(); throw error }
    }
    fun dispatch(slot: Int, payload: String?) {
        actionSequence++
        val span = "$workspace:action:$actionSequence"
        lastAction = span
        log.event("action", "started", candidate, workspace, span, detail = payload, span = span)
        try {
            // A dispatch starts the effects of its committed action, so the admission they decide with is
            // the live Session, not the one this isolate was mounted under.
            installSessionFacts()
            evaluate(DealSessionBindings.dispatch(slot, payload))
            // A clock press is admitted with the paired adapter reading taken for that press; the
            // driver resolves the issued control identity from the rendered tree. Compiled policy
            // still decides elapsed, completion and trust.
            pumpClocks(force = true)
            log.event("action", "completed", candidate, workspace, span, span = span)
        }
        catch (error: Throwable) { log.event("action", "failed", candidate, workspace, span, code = error.javaClass.simpleName, span = span); throw error }
    }
    /** Paired monotone/wall adapter readings; the host interval schedules sampling, never elapsed. */
    private fun pumpClocks(force: Boolean) {
        if (clockIntervalMs <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now < nextClockPump) return
        evaluate(DealSessionBindings.clockPump(now, System.currentTimeMillis(), force))
        nextClockPump = now + clockIntervalMs
    }
    internal fun frameVisibility(visible:Boolean){
        // A visible surface is a Session activation/resume; the driver lane publishes the epoch change
        // the expiry consumer reads, and the lifecycle transition is traced with the Session.
        if(visible)log.event("session","resumed",candidate,workspace)
        // A suspend and a resume each start a new Session clock period, so a relative expiry an
        // invocation captured under the earlier period is undecidable and compiled admission refuses
        // its reply. The facts are installed again so the generated Experience reads the new period.
        checkNotNull(capabilitySession){ "UI session is not mounted" }.periodChanged()
        installSessionFacts()
        evaluate("setFrameVisibility($visible, ${android.os.SystemClock.elapsedRealtime()})")
        // A resume revalidates expiry before the next recompute publishes, so a displayable stale value
        // is never briefly usable as a ready prerequisite.
        if(visible)driveSession(android.os.SystemClock.elapsedRealtime())
    }
    /** The three private Session drivers this lane owns: the authorized initial read after activation,
     * the head of every clock-end and entry-added trigger queue, and the deadline transition. Each is an
     * issued action dispatched at the reading this lane took, with compiled policy deciding admission. */
    private fun driveSession(monotone: Long) {
        if (driven.isEmpty()) return
        if (driven.contains("activation")) evaluate(DealSessionBindings.activate(monotone))
        if (driven.contains("triggers")) evaluate(DealSessionBindings.triggers)
        if (driven.contains("expiry")) evaluate(DealSessionBindings.expire(monotone))
    }
    /** One storage request answered by the Experience-scoped host; a failure is a typed reply. */
    private fun storageReply(request: JSONObject, started: Long): JSONObject {
        val id = request.getInt("id")
        val span = "storage:$id"
        log.event("capability", "started", candidate, workspace, id.toString(), parentOperation = lastAction, target = "host/storage." + request.optString("function"), detail = request.toString(), span = span)
        return try {
            val value = storage.call(request.getString("function"), request.getJSONArray("args"))
            log.event("capability", "completed", candidate, workspace, id.toString(), parentOperation = requestParents.remove(id) ?: "", durationMs = android.os.SystemClock.elapsedRealtime() - started, detail = value.toString(), span = span)
            JSONObject().put("id", id).put("ok", true).put("value", value)
        } catch (error: Throwable) {
            requestParents.remove(id)
            log.event("capability", "failed", candidate, workspace, id.toString(), (error as? ProtocolFailure)?.code ?: error.javaClass.simpleName, durationMs = android.os.SystemClock.elapsedRealtime() - started, span = span)
            JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", (error as? ProtocolFailure)?.code ?: "STORAGE").put("message", error.message ?: "Storage unavailable"))
        }
    }
    fun advance(): JSONObject {
        // Driver progress is independent of renderer events and detachment; a delayed or coalesced
        // host callback still reports the monotone delta that really passed. Clock and frame drivers
        // dispatch actions, so the facts of this transition are installed before any of them can send.
        installSessionFacts()
        pumpClocks(force = false)
        evaluate("advanceFrames(${android.os.SystemClock.elapsedRealtime()})")
        // The driver lane also owns the issued activation, trigger and expiry mechanisms: an authorized
        // initial read, the head of an ordered trigger queue and a due deadline all progress here,
        // without a renderer event, a published version or a host poll.
        driveSession(android.os.SystemClock.elapsedRealtime())
        val encoded = evaluate(DealSessionBindings.requests)
        check(encoded.toByteArray().size <= 16 * 1024) { "Capability request batch too large" }
        val requests = JSONArray(encoded)
        check(requests.length() <= 8) { "Capability request batch too large: " + requests.length() + " pending" }
        val now = android.os.SystemClock.elapsedRealtime()
        val capabilities = checkNotNull(capabilitySession) { "UI session is not mounted" }
        // A public probe is admitted on this lane and answered on a later one, so a slow destination
        // never delays an action, a frame or another request.
        val probeRequests = JSONArray(evaluate(DealSessionBindings.probes))
        check(probeRequests.length() <= 3) { "Public probe batch too large: " + probeRequests.length() }
        for (i in 0 until probeRequests.length()) { probes.receive(probeRequests.getJSONObject(i)) }
        val storageReplies = mutableListOf<JSONObject>()
        for (i in 0 until requests.length()) {
            val request = requests.getJSONObject(i)
            val id = request.getInt("id")
            // The per-Experience store is a host mechanism, not a disclosed provider contract: it is
            // answered here, traced on its own span and never routed through a capability session.
            if (request.optString("module") == "host/storage") {
                requestParents[id] = lastAction
                storageReplies.add(storageReply(request, now))
                continue
            }
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
            val failure = reply.optJSONObject("error")
            // The transport publishes a definite failure and an undecidable external mutation differently, so
            // the evidence keeps that difference instead of reporting every failure as the same outcome.
            log.event("capability", if (reply.getBoolean("ok")) "completed" else if (failure?.optString("outcome") == "unknown") "unknown" else "failed", candidate, workspace, id.toString(),
                failure?.optString("code") ?: "", now - started, parentOperation = requestParents.remove(id) ?: "", detail = reply.toString(), span = "${capabilities.identity}:$id")
        }
        // Completions keep their request order even when a host store call answered immediately.
        val delivered = (replies + storageReplies).sortedBy { it.getInt("id") }
        if (delivered.isNotEmpty()) {
            // A reply is admitted under the Session as it is now, never as it was when the call left.
            installSessionFacts()
            log.stage("effect-delivery", candidate, workspace, delivered.joinToString(",") { it.getInt("id").toString() }) { evaluate("dealCapabilities.deliver(${JSONArray(delivered)})") }
        }
        val probeReplies = probes.drain()
        if (probeReplies.isNotEmpty()) {
            log.event("probe", "completed", candidate, workspace, probeReplies.joinToString(",") { it.getInt("id").toString() })
            evaluate("dealProbe.deliver(${JSONArray(probeReplies)})")
        }
        val snapshot = JSONObject(evaluate(DealSessionBindings.snapshot))
        if (snapshot.getInt("version") != lastVersion || snapshot.optString("fault").isNotEmpty() != lastFault) recordSnapshot(snapshot)
        return snapshot
    }
    /** Optional bounded observation of production, not a second update or renderer delivery path. */
    private fun recordSnapshot(snapshot: JSONObject) {
        lastVersion = snapshot.getInt("version"); lastFault = snapshot.optString("fault").isNotEmpty()
        log.event("snapshot", if (lastFault) "fault" else "produced", candidate, workspace, version = lastVersion, parentOperation = lastAction,
            detail = if (log.capturesContent) runCatching { observationValue(snapshot).toString() }.getOrNull() else null)
    }
    /** Match public protocol transport: compiler nominal metadata is not snapshot data. */
    private fun observationValue(value: Any): Any = when(value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> if (!key.startsWith("$")) out.put(key, observationValue(value.get(key))) } }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(observationValue(value.get(i))) }
        else -> value
    }
    fun published(version: Int) { require(version>=0 && version<=lastVersion) { "Unproduced snapshot version" }; log.event("snapshot", "published", candidate, workspace, version = version) }
    override fun close() {
        if (!closed) {
            closed = true
            requestStarts.keys.forEach { log.event("capability", "cancelled", candidate, workspace, it.toString()) }
            requestStarts.clear(); requestParents.clear()
            try { program.evaluate(DealSessionBindings.dispose) } catch (_: Exception) { /* Terminated isolates cannot be disposed. */ }
            finally { try { probes.close(); program.close() } finally { capabilitySession?.close(); capabilitySession = null } }
        }
    }
}

