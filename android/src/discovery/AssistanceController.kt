package dev.deal.embedding.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.deal.embedding.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Executes the compiled assistance lifecycle: it supplies the approved facts, enablement, retained
 * dismissal and saved links, runs the transport calls and owns the display. Open, reuse,
 * revalidation, activation ordering, Dismiss retention and failure classification are compiled policy.
 */
internal class AssistanceController(private val context: Context, private val facts: () -> List<DiscoveryFact>, private val protocolFactory: (Context) -> ApplicationProtocol, private val hooks: AssistanceHooks) : AutoCloseable {
    /** The host-configured bounds this controller's protocol carries to rendered resources. */
    internal val resourceLimits get() = api?.resourceLimits ?: ExecutionLimits()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // The compiled policy is created on the first decision, on the assistance worker rather than during
    // composition; a pause before any attempt has nothing to invalidate.
    @Volatile private var compiled: AssistancePolicy? = null
    private fun lifecycle(): AssistancePolicy = compiled ?: synchronized(this) {
        compiled ?: AssistancePolicy(context).also { compiled = it }
    }
    @Volatile private var foreground = false
    @Volatile private var epoch = 0L
    @Volatile private var api: ApplicationProtocol? = null
    @Volatile private var finder: Discovery? = null
    @Volatile private var request: GenerationRequest? = null
    @Volatile private var modules: List<String> = emptyList()
    private var factsInput: DiscoveryFacts? = null
    private var owner: ExperienceSurface? = null
    private var ownerInput: String? = null
    var suggestion by mutableStateOf<DiscoverySuggestion?>(null)
    var surface by mutableStateOf<ExperienceSurface?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
        private set
    var trace by mutableStateOf("")
        private set
    var since by mutableLongStateOf(0L)
        private set
    init { hooks.register(this) { reset() } }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else {
            val task = java.util.concurrent.FutureTask { action() }; main.post(task); task.get()
        }
    }
    private fun reset() {
        onMain { pause() }
        if (worker.isShutdown) {
            check(worker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) { "Assistance cleanup still pending" }
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() }, { compiled?.close() })
            hooks.unregister(this)
            return
        }
        worker.submit {
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() }, { compiled?.reset() })
            finder = null; owner = null; api = null; ownerInput = null; factsInput = null; request = null
        }.get(30, java.util.concurrent.TimeUnit.SECONDS)
        onMain { failed = false; status = ""; trace = ""; since = 0 }
    }
    private fun post(version: Long, action: () -> Unit) { main.post { if (foreground && version == epoch) action() } }
    fun pause() {
        foreground = false; epoch++
        // Invalidate the compiled attempt before cancelling outstanding work: a cancellation completion
        // must be ignored, never recorded as this suggestion's failure.
        compiled?.let { attempted -> runCatching { attempted.abandon() } }
        finder?.cancel(); suggestion = null; surface = null; busy = false
        request?.let { api?.cancel(it) }
        // Grants have foreground lifetime; settings preserve only the user's decisions.
        api?.let { protocol -> modules.forEach(protocol::revoke) }
    }
    private fun assistanceInput(identity: String, saved: List<String> = emptyList(), link: String = "") =
        AssistanceInput(identity, hooks.discoveryEnabled(), hooks.generationEnabled(), saved, link, hooks.dismissed(identity))
    private fun suggestionJson(tip: DiscoverySuggestion, identity: String) = JSONObject()
        .put("title", tip.title).put("brief", tip.brief)
        .put("supportingFactIds", JSONArray(tip.supportingFactIds))
        .put("capabilityIds", JSONArray(tip.capabilityIds)).put("identity", identity)
    fun resume() {
        if (foreground || hooks.resetting()) return
        foreground = true
        val version = ++epoch
        if (!failed) status = "Looking ahead…"
        busy = true
        worker.execute {
            var token = ""
            try {
                val current = DiscoveryFacts(facts())
                factsInput = current
                val protocol = api ?: protocolFactory(context).also { api = it }
                modules = hooks.applyGrants(protocol)
                check(foreground && version == epoch)
                val saved = protocol.saved()
                val decision = lifecycle().resume(assistanceInput(current.identity, saved.map { it.id }, hooks.saved(current.identity) ?: ""))
                token = decision.token
                // A displayed Experience belongs to the fact identity it was prepared for.
                if (owner != null && ownerInput != current.identity) { owner?.close(); owner = null; ownerInput = null }
                when (decision.action) {
                    // Compiled policy: a failed attempt is never silently rerun on resume.
                    "keep" -> post(version) { busy = false }
                    "idle" -> post(version) { busy = false; status = "" }
                    "busy" -> post(version) { busy = false }
                    "reuse" -> {
                        val retained = saved.firstOrNull { it.id == decision.experience }
                        if (retained == null) {
                            runCatching { lifecycle().failed(decision.token, "FAILED", current.identity) }
                            post(version) { busy = false; failed = true; status = "Help unavailable" }
                        } else {
                            activate(protocol, retained, version); ownerInput = current.identity
                            lifecycle().reused(decision.token)
                            post(version) { busy = false; status = "" }
                        }
                    }
                    else -> {
                        val discovery = finder ?: protocol.discovery.also { finder = it }
                        since = System.currentTimeMillis(); trace = ""
                        discovery.discover(current).whenComplete { tip, error ->
                            // The compiled lifecycle decides whether a superseded or abstained result is issuable.
                            if (error == null) runCatching { lifecycle().suggested(decision.token, if (tip == null) "" else suggestionJson(tip, current.identity).toString()) }
                            // Cancellation invalidates the attempt; it is never this suggestion's failure.
                            if (error is java.util.concurrent.CancellationException) runCatching { lifecycle().abandon(decision.token) }
                            else if (error != null) runCatching { lifecycle().failed(decision.token, "FAILED", current.identity) }
                            post(version) {
                                busy = false; suggestion = tip; failed = error != null
                                status = if (error != null) "Couldn’t find help" else ""
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                // Never strand the compiled lane: a throw before its completion leaves the attempt open.
                if (token.isNotEmpty()) runCatching { compiled?.abandon(token) }
                post(version) { busy = false; failed = true; status = "Help unavailable" }
            }
        }
    }
    fun presentationFailure(error: Throwable) {
        api?.let { protocol -> runCatching { EmbeddingLog.traced(context,trace).event("host","failed",target="Assistance",detail=android.util.Log.getStackTraceString(error)) } }
        suggestion = null; busy = false; failed = true; status = "Help unavailable"
    }
    fun retry() {
        if (busy || !failed) return
        // An explicit retry clears a failure the compiled policy recorded; adapter-origin failures
        // (presentation, activation or a pre-attempt fault) have none to clear.
        compiled?.retry()
        failed = false; status = ""; trace = ""
        pause(); resume()
    }
    fun dismiss() {
        val identity = factsInput?.identity ?: return
        try {
            lifecycle().dismiss(identity)
        } catch (error: ProtocolFailure) {
            // A duplicate Dismiss while one attempt is outstanding dispatches nothing.
            if (error.code == "BUSY") return
            throw error
        }
        hooks.dismiss(identity)
        finder?.cancel(); suggestion = null; status = ""
    }
    fun open(tip: DiscoverySuggestion) {
        if (busy) return
        val version = epoch; val captured = factsInput ?: return
        suggestion = null; busy = true; status = "Preparing…"
        worker.execute {
            var token = ""
            try {
                val protocol = checkNotNull(api)
                val saved = protocol.saved()
                // Which facts are approved now, not the ones captured at discovery time.
                val discovery = checkNotNull(finder)
                val current = DiscoveryFacts(facts())
                // Rule 1: the canonical compiled admission revalidates the facts and refreshed catalogue
                // before either reuse or generation is decided. The generate path revalidates again at
                // request time, so a catalogue change between the two is never admitted.
                check(foreground && version == epoch)
                discovery.admit(tip, current)
                val decision = lifecycle().open(assistanceInput(current.identity, saved.map { it.id }, hooks.saved(current.identity) ?: ""))
                token = decision.token
                when (decision.action) {
                    // Reuse activates the saved link without a request or inference.
                    "reuse" -> {
                        val retained = saved.firstOrNull { it.id == decision.experience }
                            ?: throw ProtocolFailure("FAILED", "Saved Experience missing")
                        check(foreground && version == epoch && DiscoveryFacts(facts()).identity == captured.identity)
                        hooks.validateCurrent()
                        activate(protocol, retained, version); ownerInput = captured.identity
                        lifecycle().reused(token)
                    }
                    else -> {
                        check(foreground && version == epoch)
                        hooks.validateCurrent()
                        val handoff = discovery.request(tip, DiscoveryFacts(facts())); request = handoff
                        trace = protocol.requestTrace(handoff)
                        if (!foreground || version != epoch) protocol.cancel(handoff)
                        protocol.withPreparation(protocol.generate(handoff)) { experience ->
                            // Activation revalidates the approved facts and enablement before mounting.
                            val activation = lifecycle().generated(token, assistanceInput(DiscoveryFacts(facts()).identity, saved.map { it.id }))
                            hooks.validateCurrent()
                            activate(protocol, experience, version)
                            // The compiled lifecycle orders the saved link after activation.
                            val link = lifecycle().activated(activation.token, experience.id)
                            check(foreground && version == epoch) { "Assistance changed before saving link" }
                            hooks.save(link.identity, experience.id); ownerInput = captured.identity
                        }
                    }
                }
                post(version) { busy = false; status = "" }
            } catch (error: Throwable) {
                val code = (error as? ProtocolFailure)?.code ?: "FAILED"
                // A completion for a superseded attempt is ignored: cancel it, never classify it.
                val superseded = !foreground || version != epoch
                if (superseded && token.isNotEmpty()) runCatching { compiled?.abandon(token) }
                // A refusal that started no attempt changes no lifecycle state: STALE invalidates the
                // suggestion, while INVALID rejects this attempt's evidence.
                val outcome = if (superseded || token.isEmpty()) null else runCatching { lifecycle().failed(token, code, captured.identity) }.getOrNull()?.outcome
                // The located reason is evidence for Inspect, never user copy.
                if (code == "STALE" || code == "INVALID") runCatching { EmbeddingLog.traced(context, trace).event("host", "rejected", target = "Assistance", code = code, detail = error.message ?: "") }
                if (token.isEmpty() && code == "STALE") runCatching { finder?.cancel() }
                post(version) {
                    busy = false
                    when {
                        token.isEmpty() && code == "BUSY" -> Unit
                        // A disabled refusal changes nothing; the suggestion stays displayed.
                        token.isEmpty() && code == "DISABLED" -> { suggestion = tip; status = "Enable tailored tools in Settings" }
                        token.isEmpty() && code == "INVALID" -> { suggestion = null; failed = true; status = "Couldn’t create this tool" }
                        token.isEmpty() && code == "STALE" -> { suggestion = null; status = "This suggestion is no longer current" }
                        outcome == "invalidate" -> { suggestion = null; status = "This suggestion is no longer current" }
                        else -> { failed = true; status = "Couldn’t create this tool" }
                    }
                }
            }
            finally { request = null }
        }
    }
    private fun activate(protocol: ApplicationProtocol, experience: Experience, version: Long) {
        check(foreground && version == epoch)
        hooks.validateCurrent()
        hooks.activating()
        val next = if (owner?.experience?.id == experience.id) checkNotNull(owner) else ExperienceSurface(protocol, experience)
        try {
            next.activate { post(version) { failed = true; status = "Tool unavailable" } }
            check(foreground && version == epoch)
            val previous = owner; owner = next
            post(version) { surface = next }
            if (previous !== next) previous?.close()
        } catch (error: Throwable) { if (next !== owner) runCatching { next.close() }.onFailure(error::addSuppressed); throw error }
    }
    override fun close() {
        pause()
        worker.execute { try {
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() }, { compiled?.close() })
            hooks.unregister(this)
        } catch (error: Throwable) { android.util.Log.e("Assistance", "Cleanup failed", error) } }
        worker.shutdown()
    }
}
