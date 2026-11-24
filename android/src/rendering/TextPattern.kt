package dev.deal.embedding

import android.content.Context
import org.json.JSONObject

/** Full ECMAScript Unicode patterns inside the existing bounded sandbox; no native regex fallback. */
internal class TextPattern(private val context: Context, private val limits: ExecutionLimits) : AutoCloseable {
    private var engine: androidx.javascriptengine.JavaScriptSandbox? = null
    private var program: SandboxProgram? = null
    private var closed = false

    @Synchronized fun matches(pattern: String, text: String): Boolean {
        check(!closed) { "Text pattern evaluator is closed" }
        val active = program ?: run {
            val sandbox = SandboxEngine.acquire(context)
            engine = sandbox
            try {
                SandboxProgram(sandbox, 1024, limits).also {
                    program = it
                    val script = context.assets.open("embedding/bindings/text-pattern.js").bufferedReader().use { reader -> reader.readText() }
                    it.evaluate(script + "\n''")
                }
            } catch (error: Throwable) { close(); throw error }
        }
        val result = JSONObject(active.evaluate("textPattern(${JSONObject.quote(pattern)},${JSONObject.quote(text)})"))
        if (!result.getBoolean("valid")) throw ProtocolFailure("PROP_FAULT", "TextField.pattern: invalid ECMAScript Unicode expression")
        return result.getBoolean("matches")
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { program?.close() }
        finally { engine?.let { SandboxEngine.release(it) }; program = null; engine = null }
    }
}
