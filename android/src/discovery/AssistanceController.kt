package dev.deal.embedding.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.deal.embedding.*
import java.util.concurrent.Executors

internal class AssistanceController(private val context: Context, private val facts: () -> List<DiscoveryFact>, private val protocolFactory: (Context) -> ApplicationProtocol, private val hooks: AssistanceHooks) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foreground = false
    @Volatile private var epoch = 0L
    @Volatile private var api: ApplicationProtocol? = null
    @Volatile private var finder: Discovery? = null
    @Volatile private var request: GenerationRequest? = null
    @Volatile private var modules: List<String> = emptyList()
    private var input: DiscoveryFacts? = null
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
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() })
            hooks.unregister(this)
            return
        }
        worker.submit {
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() })
            finder = null; owner = null; api = null; ownerInput = null; input = null; request = null
        }.get(30, java.util.concurrent.TimeUnit.SECONDS)
        onMain { failed = false; status = ""; trace = ""; since = 0 }
    }
    private fun post(version: Long, action: () -> Unit) { main.post { if (foreground && version == epoch) action() } }
    fun pause() {
        foreground = false; epoch++; finder?.cancel(); suggestion = null; surface = null; busy = false
        request?.let { api?.cancel(it) }
        // Grants have foreground lifetime; settings preserve only the user's decisions.
        api?.let { protocol -> modules.forEach(protocol::revoke) }
    }
    fun resume() {
        if (foreground || hooks.resetting()) return
        foreground = true
        val version = ++epoch
        if (!failed) status = "Looking ahead…"
        busy = true
        worker.execute {
            try {
                val current = DiscoveryFacts(facts())
                val sameInput = input?.identity == current.identity
                input = current
                // Returning from Inspect/Settings must not silently rerun a failed attempt.
                if (sameInput && failed) {
                    api?.let { modules = hooks.applyGrants(it) }
                    post(version) { busy = false }; return@execute
                }
                val protocol = api ?: protocolFactory(context).also { api = it }
                modules = hooks.applyGrants(protocol)
                check(foreground && version == epoch)
                if (owner != null && ownerInput != current.identity) { owner?.close(); owner = null; ownerInput = null }
                val retained = hooks.saved(current.identity)
                val saved = retained?.let { id -> protocol.saved().firstOrNull { it.id == id } }
                if (saved != null) { activate(protocol, saved, version); ownerInput = current.identity; post(version) { busy = false; status = "" }; return@execute }
                if (!hooks.discoveryEnabled() || hooks.dismissed(current.identity)) {
                    post(version) { busy = false; status = "" }; return@execute
                }
                val discovery = finder ?: protocol.discovery.also { finder = it }
                since = System.currentTimeMillis(); trace = ""
                discovery.discover(current).whenComplete { tip, error -> post(version) {
                    busy = false; suggestion = tip; failed = error != null
                    status = if (error != null) "Couldn’t find help" else ""
                } }
            } catch (error: Throwable) { post(version) { busy = false; failed = true; status = "Help unavailable" } }
        }
    }
    fun presentationFailure(error: Throwable) {
        api?.let { protocol -> runCatching { EmbeddingLog.traced(context,trace).event("host","failed",target="Assistance",detail=android.util.Log.getStackTraceString(error)) } }
        suggestion = null; busy = false; failed = true; status = "Help unavailable"
    }
    fun retry() {
        if (busy || !failed) return
        failed = false; status = ""; trace = ""
        pause(); resume()
    }
    fun dismiss() {
        input?.let { hooks.dismiss(it.identity) }
        finder?.cancel(); suggestion = null; status = ""
    }
    fun open(tip: DiscoverySuggestion) {
        if (busy) return
        if (!hooks.generationEnabled()) { status = "Enable tailored tools in Settings"; return }
        val version = epoch; val captured = input ?: return
        suggestion = null; busy = true; status = "Preparing…"
        worker.execute {
            try {
                val protocol = checkNotNull(api)
                check(foreground && version == epoch && hooks.generationEnabled())
                val current = DiscoveryFacts(facts())
                hooks.validateCurrent()
                val admitted = checkNotNull(finder).request(tip, current); request = admitted
                trace = protocol.requestTrace(admitted)
                if (!foreground || version != epoch) protocol.cancel(admitted)
                protocol.withPreparation(protocol.generate(admitted)) { experience ->
                    check(foreground && version == epoch && DiscoveryFacts(facts()).identity == captured.identity)
                    hooks.validateCurrent()
                    activate(protocol, experience, version); ownerInput = captured.identity
                    check(foreground && version == epoch) { "Assistance changed before saving link" }
                    hooks.save(captured.identity, experience.id)
                }
                post(version) { busy = false; status = "" }
            } catch (error: Throwable) { post(version) { busy = false; failed = true; status = "Couldn’t create this tool" } }
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
            teardown({ finder?.close() }, { owner?.close() }, { api?.close() })
            hooks.unregister(this)
        } catch (error: Throwable) { android.util.Log.e("Assistance", "Cleanup failed", error) } }
        worker.shutdown()
    }
}
