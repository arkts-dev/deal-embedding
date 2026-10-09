package dev.deal.embedding

import android.content.Context
import android.os.SystemClock
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Trusted observation only. Admission and persistence failures never replace application outcomes. */
class EmbeddingLog private constructor(private val context: Context, val run: String, private val defaultParent: String) {
    constructor(context: Context) : this(context.applicationContext, UUID.randomUUID().toString(), "")
    private fun child(parent: String) = EmbeddingLog(context, run, parent)
    val capturesContent: Boolean get() = runCatching { context.getSharedPreferences("embedding-logging", 0).getBoolean("content", false) }.getOrDefault(false)
    fun event(stage: String, outcome: String, candidate: String = "", workspace: String = "", operation: String = "", code: String = "", durationMs: Long = 0, version: Int = -1, parentOperation: String = "", target: String = "", detail: String? = null, span: String = UUID.randomUUID().toString()) {
        try {
            sink(context).submit(JSONObject().put("trace", run).put("span", span).put("parent", parentOperation.ifEmpty { defaultParent })
                .put("stage", stage).put("outcome", outcome).put("candidate", candidate).put("workspace", workspace).put("operation", operation)
                .put("code", code).put("durationMs", durationMs.coerceIn(0, Int.MAX_VALUE.toLong())).put("version", version).put("target", target), detail, capturesContent)
        } catch (error: Exception) { emergency(context, error) }
    }
    internal fun <T> stage(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: () -> T): T = operation(name, candidate, workspace, operation, target, detail) { block() }
    internal fun <T> operation(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: (EmbeddingLog) -> T): T {
        val span = UUID.randomUUID().toString(); val start = SystemClock.elapsedRealtime()
        event(name, "started", candidate, workspace, operation, target = target, detail = detail, span = span)
        try { return block(child(span)).also { event(name, "completed", candidate, workspace, operation, durationMs = SystemClock.elapsedRealtime() - start, target = target, span = span) } }
        catch (error: Throwable) {
            val code = when (error) { is CandidateRejected -> "CANDIDATE_REJECTED"; is deal.ui.UiDiagnostic -> error.code(); is java.util.concurrent.CancellationException -> "CANCELLED"; else -> error.javaClass.simpleName }
            event(name, "failed", candidate, workspace, operation, code, SystemClock.elapsedRealtime() - start, target = target, detail = if (error is CandidateRejected) error.diagnostics else null, span = span)
            throw error
        }
    }
    companion object {
        @Volatile private var instance: Sink? = null
        private fun sink(context: Context): Sink = instance ?: synchronized(this) { instance ?: Sink(context.applicationContext).also { instance = it } }
        fun capture(context: Context, enabled: Boolean) { check(context.getSharedPreferences("embedding-logging", 0).edit().putBoolean("content", enabled).commit()) }
        internal fun flush(context: Context) { sink(context).executor.submit {}.get(10, TimeUnit.SECONDS) }
        fun clear(context: Context) { sink(context).executor.execute { directory(context).listFiles()?.forEach { it.delete() }; sink(context).resetHealth() } }
        private fun directory(context: Context) = File(context.filesDir, "embedding-log")
        private fun emergency(context: Context, error: Exception) {
            android.util.Log.e("DealLoggerEmergency", "Logger unavailable: ${error.javaClass.simpleName}")
            runCatching { directory(context).apply { mkdirs() }.resolve("health.json").writeText(JSONObject().put("incomplete", true).put("active", true).put("code", "LOGGER_FAILED").put("time", System.currentTimeMillis().toString()).toString()) }
        }
    }
    private class Sink(private val context: Context) {
        val executor = Executors.newSingleThreadExecutor()
        private var queued = 0; private var dropped = 0
        @Volatile private var queueLimit = 256
        private var engine: JavaScriptSandbox? = null; private var program: SandboxProgram? = null
        private var sequence = 0
        private val directory = directory(context).apply { mkdirs() }
        private var health = runCatching { JSONObject(File(directory, "health.json").readText()) }.getOrNull()
        private var emergencyReported = false
        init {
            listOf("workspace-error.txt", "generation-trace.txt", "embedding-receipts.jsonl", "embedding-receipts.previous.jsonl").forEach { File(context.filesDir, it).delete() }
            context.getSharedPreferences("embedding-debug", 0).edit().remove("captureReplay").apply()
        }
        fun resetHealth() { health = null; emergencyReported = false }
        private fun fault(code: String, count: Int) {
            health = JSONObject().put("incomplete", true).put("active", true).put("code", code).put("time", System.currentTimeMillis().toString())
                .put("lost", (health?.optInt("lost") ?: 0) + count)
            runCatching { File(directory, "health.json").writeText(health.toString()) }
        }
        private fun recovered() {
            emergencyReported = false
            if (health != null && health!!.optBoolean("active", true)) {
                health!!.put("active", false).put("recoveredAt", System.currentTimeMillis().toString())
                runCatching { File(directory, "health.json").writeText(health.toString()) }
            }
        }
        @Synchronized fun submit(value: JSONObject, detail: String?, capture: Boolean) {
            if (queued >= queueLimit) { dropped++; return }
            val bytes = detail?.toByteArray(Charsets.UTF_8)
            value.put("bytes", bytes?.size ?: 0)
            val retained = if (capture) bytes?.copyOfRange(0, minOf(bytes.size, 262144)) else null
            val truncated = retained != null && retained.size != bytes!!.size
            queued++
            try { executor.execute {
                try {
                    val lost = synchronized(this) { val n = dropped; dropped = 0; n }
                    if (lost > 0) {
                        fault("QUEUE_LIMIT", lost)
                        write(JSONObject().put("trace", "logger").put("span", UUID.randomUUID().toString()).put("stage", "logger").put("outcome", "dropped").put("code", "QUEUE_LIMIT").put("bytes", lost), null, false, false)
                    }
                    write(value, retained, capture, truncated)
                    recovered()
                } catch (error: Exception) {
                    fault("LOGGER_FAILED", 1)
                    if (!emergencyReported) { android.util.Log.e("DealLoggerEmergency", "Logger unavailable: ${error.javaClass.simpleName}"); emergencyReported = true }
                    // Storage failure does not invalidate either sandbox. Only an unusable logger isolate is released.
                    if (error !is java.io.IOException) {
                        val dead = generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException }
                        runCatching { program?.close() }; runCatching { engine?.let { SandboxEngine.release(it, dead) } }
                        program = null; engine = null
                    }
                } finally { synchronized(this) { queued-- } }
            } } catch (error: Exception) { queued--; throw error }
        }
        private fun write(value: JSONObject, bytes: ByteArray?, capture: Boolean, truncated: Boolean) {
            if (program == null) {
                engine = SandboxEngine.acquire(context)
                program = SandboxProgram(engine!!, 512 * 1024)
                program!!.evaluate(SandboxBundle.build(context, listOf("logging-session.js"), "configureDealCapabilities([]);", "initializeLogger(factories)", assetRoot = "logging"))
            }
            value.put("sequence", ++sequence).put("time", System.currentTimeMillis().toString()).put("elapsed", SystemClock.elapsedRealtime().toString()).put("capture", capture)
            val artifact = if (bytes != null && capture) UUID.randomUUID().toString() else ""
            value.put("artifact", artifact)
            val normalized = JSONObject(program!!.evaluate("normalizeLog(${value})"))
            if (artifact.isNotEmpty()) {
                val stored = bytes!!
                check(stored.size <= normalized.getInt("contentLimit"))
                val files = directory.listFiles()?.filter { it.extension == "json" && it.name != "health.json" }?.sortedBy { it.lastModified() }.orEmpty()
                var used = files.sumOf { it.length() }
                for (file in files) { if (used + stored.size <= normalized.getInt("artifactStoreLimit")) break; used -= file.length(); check(file.delete()) }
                File(directory, "$artifact.json").writeBytes(stored)
                normalized.put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) })
                if (truncated) normalized.put("content", "truncated")
            }
            val eventStoreLimit = normalized.getInt("eventStoreLimit")
            queueLimit = normalized.getInt("queueLimit")
            normalized.remove("contentLimit"); normalized.remove("artifactStoreLimit"); normalized.remove("eventStoreLimit"); normalized.remove("queueLimit")
            val file = File(directory, "events.jsonl")
            if (file.length() + normalized.toString().toByteArray().size > eventStoreLimit) {
                val previous = File(directory, "previous.jsonl"); if (previous.exists()) check(previous.delete()); if (file.exists()) check(file.renameTo(previous))
            }
            file.appendText(normalized.toString() + "\n")
        }
    }
}
