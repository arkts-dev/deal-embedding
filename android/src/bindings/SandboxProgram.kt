package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Android permits one connected sandbox service per process; isolates retain separate lifetimes. */
internal object SandboxEngine {
    private var engine: JavaScriptSandbox? = null
    private var leases = 0
    @Synchronized fun acquire(context: Context): JavaScriptSandbox {
        val active = engine ?: JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get(10, TimeUnit.SECONDS).also { engine = it }
        leases++; return active
    }
    @Synchronized fun release(active: JavaScriptSandbox, dead: Boolean = false) {
        if (active !== engine) return
        leases--
        if (dead || leases == 0) { active.close(); engine = null; leases = 0 }
    }
}

/** Only execution mechanics are shared. Callers select their own bindings and authority. */
internal class SandboxProgram(engine: JavaScriptSandbox, returnLimit: Int) : AutoCloseable {
    private val isolate: JavaScriptIsolate
    private var closed = false
    init {
        listOf(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN, JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION, JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE).forEach {
            check(engine.isFeatureSupported(it)) { "Missing sandbox feature: $it" }
        }
        isolate = engine.createIsolate(IsolateStartupParameters().apply { setMaxHeapSizeBytes(32L * 1024 * 1024); setMaxEvaluationReturnSizeBytes(returnLimit) })
    }
    fun evaluate(source: String): String {
        check(!closed)
        try { return isolate.evaluateJavaScriptAsync(source).get(2, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.TimeoutException) { close(); throw error }
    }
    override fun close() { if (!closed) { closed = true; isolate.close() } }
}

internal object SandboxBundle {
    private fun StringBuilder.asset(context: Context, path: String) {
        append(context.assets.open(path).bufferedReader().use { it.readText() }).append('\n')
    }
    private fun StringBuilder.factory(key: String, source: String) {
        append("factories[").append(JSONObject.quote(key)).append("] = function(require,module,exports,process,console) {\n")
        append(source).append("\n};\n")
    }
    fun build(context: Context, bindings: List<String>, configure: String, invocation: String, output: File? = null, assetRoot: String? = null): String = buildString {
        require((output == null) != (assetRoot == null))
        asset(context, "embedding/bindings/sandbox.js")
        bindings.forEach { asset(context, "embedding/bindings/$it") }
        append(configure).append("\n(() => { const factories = Object.create(null);\n")
        if (output != null) {
            output.walkTopDown().filter { it.isFile && it.extension == "js" }.sortedBy { it.path }.forEach {
                factory(it.relativeTo(output).invariantSeparatorsPath, it.readText())
            }
        } else {
            fun add(path: String, relative: String) {
                for (name in context.assets.list(path) ?: emptyArray()) {
                    val asset = "$path/$name"; val key = if (relative.isEmpty()) name else "$relative/$name"
                    if (name.endsWith(".js")) factory(key, context.assets.open(asset).bufferedReader().use { it.readText() })
                    else add(asset, key)
                }
            }
            add(assetRoot!!, "")
        }
        append("return ").append(invocation).append("; })();")
    }
}
