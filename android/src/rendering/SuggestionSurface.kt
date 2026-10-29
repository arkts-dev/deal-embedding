package dev.deal.embedding

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.deal.embedding.discovery.DiscoverySuggestion
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Fixed trusted UI, not a generated experience. Its two bindings carry no generic API authority. */
internal class SuggestionSurface(context: Context, title: String, private val open: () -> Unit,
                                 private val dismiss: () -> Unit, private val failed: (Throwable) -> Unit) : AutoCloseable {
    private val engine = SandboxEngine.acquire(context)
    private val program = SandboxProgram(engine, 256 * 1024)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    var tree: JSONObject? by mutableStateOf(null)
        private set
    init {
        try {
            val contract = "[{module:'embedding/assistance',functions:[{name:'open',parameters:[],result:{kind:'null'}},{name:'dismiss',parameters:[],result:{kind:'null'}}]}]"
            program.evaluate(SandboxBundle.build(context,listOf("dealui-session.js","ui-session.js"),
                "configureDealCapabilities($contract);", "mountDealUi(factories,'experience_entry.js',${JSONObject().put("title",title).put("busy",false)})",assetRoot="assistance"))
            tree = JSONObject(program.evaluate(DealSessionBindings.snapshot)).getJSONObject("tree")
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed); throw error
        }
    }
    fun dispatch(slot: Int, payload: String?) {
        worker.execute {
            if (closed) return@execute
            try {
            program.evaluate(DealSessionBindings.dispatch(slot,payload))
            val requests = JSONArray(program.evaluate(DealSessionBindings.requests))
            for (i in 0 until requests.length()) {
                val request = requests.getJSONObject(i)
                check(request.getString("module") == "embedding/assistance" && request.getJSONArray("args").length() == 0)
                val action = when (request.getString("function")) { "open" -> open; "dismiss" -> dismiss; else -> error("Unknown assistance action") }
                val invoked = java.util.concurrent.FutureTask { if (!closed) action() }
                main.post(invoked); invoked.get()
                if (closed) return@execute
                program.evaluate("dealCapabilities.deliver([${JSONObject().put("id",request.getInt("id")).put("ok",true).put("value",JSONObject.NULL)}])")
            }
            // Promise settlement crosses an evaluation turn before the checked tree is read.
            val snapshot = JSONObject(program.evaluate(DealSessionBindings.snapshot)).getJSONObject("tree")
            main.post { if (!closed) tree = snapshot }
            } catch (error: Throwable) { main.post { if (!closed) failed(error) } }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        teardown({ worker.shutdownNow() }, { program.close() }, { SandboxEngine.release(engine) })
    }
}

@Composable internal fun SuggestionCard(context: Context, suggestion: DiscoverySuggestion, open: () -> Unit, dismiss: () -> Unit, failed: (Throwable) -> Unit) {
    var surface by remember(suggestion) { mutableStateOf<SuggestionSurface?>(null) }
    DisposableEffect(suggestion) {
        val worker = Executors.newSingleThreadExecutor(); val main = Handler(Looper.getMainLooper())
        val lock = Any(); var closed = false; var owner: SuggestionSurface? = null
        worker.execute {
            try {
                val next = SuggestionSurface(context.applicationContext,suggestion.title,open,dismiss,failed)
                synchronized(lock) { if (closed) next.close() else { owner=next; main.post { synchronized(lock) { if (!closed) surface=next } } } }
            } catch (error: Throwable) { main.post { synchronized(lock) { if (!closed) failed(error) } } }
        }
        onDispose { synchronized(lock) { closed=true; owner?.let { worker.execute { it.close() } } }; worker.shutdown() }
    }
    surface?.let { owner -> owner.tree?.let { DealRenderer(it,owner::dispatch) } }
}
