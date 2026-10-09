package dev.deal.embedding

import android.content.Context
import android.os.SystemClock
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Mechanisms only: independent logger isolate, one bounded writer, private storage and clock. */
class EmbeddingLog(private val context: Context, val run: String = UUID.randomUUID().toString(), private val defaultParent: String = "") {
    fun child(parent: String) = EmbeddingLog(context, run, parent)
    fun event(stage: String, outcome: String, candidate: String = "", workspace: String = "", operation: String = "", code: String = "", durationMs: Long = 0, version: Int = -1, parentOperation: String = "", target: String = "", detail: String? = null, span: String = UUID.randomUUID().toString()) {
        sink(context).submit(JSONObject().put("trace", run).put("span", span).put("parent", parentOperation.ifEmpty { defaultParent })
            .put("stage", stage).put("outcome", outcome).put("candidate", candidate).put("workspace", workspace).put("operation", operation)
            .put("code", code).put("durationMs", durationMs.coerceIn(0, Int.MAX_VALUE.toLong())).put("version", version).put("target", target), detail)
    }
    fun <T> stage(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: () -> T): T = operation(name, candidate, workspace, operation, target, detail) { block() }
    fun <T> operation(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: (EmbeddingLog) -> T): T {
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
        fun capture(context: Context, enabled: Boolean) { context.getSharedPreferences("embedding-logging", 0).edit().putBoolean("content", enabled).commit() }
        fun flush(context: Context) { sink(context).executor.submit {}.get(10, TimeUnit.SECONDS) }
        fun clear(context: Context) { flush(context); sink(context).executor.submit { directory(context).listFiles()?.forEach { it.delete() } }.get(10, TimeUnit.SECONDS) }
        fun directory(context: Context) = File(context.filesDir, "embedding-log")
    }
    private class Sink(private val context: Context) {
        val executor = Executors.newSingleThreadExecutor()
        private var queued = 0; private var dropped = 0
        @Volatile private var queueLimit = 256
        private var engine: JavaScriptSandbox? = null; private var program: SandboxProgram? = null
        private var sequence = 0
        private var emergencyReported = false
        private val directory = directory(context).apply { mkdirs() }
        init {
            File(context.filesDir, "workspace-error.txt").delete()
            File(context.filesDir, "generation-trace.txt").delete()
            File(context.filesDir, "embedding-receipts.jsonl").delete()
            File(context.filesDir, "embedding-receipts.previous.jsonl").delete()
            context.getSharedPreferences("embedding-debug", 0).edit().remove("captureReplay").commit()
        }
        @Synchronized fun submit(value: JSONObject, detail: String?) {
            if (queued >= queueLimit) { dropped++; runCatching { File(directory, "health.json").writeText("{\"incomplete\":true,\"code\":\"QUEUE_LIMIT\"}") }; return }
            queued++
            val capture = context.getSharedPreferences("embedding-logging", 0).getBoolean("content", false)
            value.put("bytes", detail?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            val retainedDetail = if (capture) detail?.take(262144) else null
            if (capture && detail != null && retainedDetail!!.length != detail.length) value.put("admissionTruncated", true)
            executor.execute {
                try {
                    val lost = synchronized(this) { val n = dropped; dropped = 0; n }
                    if (lost > 0) write(JSONObject().put("trace", "logger").put("span", UUID.randomUUID().toString()).put("stage", "logger").put("outcome", "dropped").put("code", "QUEUE_LIMIT").put("bytes", lost), null, false)
                    write(value, retainedDetail, capture)
                } catch (error: Exception) {
                    synchronized(this) { dropped++ }
                    if (!emergencyReported) { android.util.Log.e("DealLoggerEmergency", "Logger unavailable: ${error.javaClass.simpleName}"); emergencyReported = true }
                    runCatching { File(directory, "health.json").writeText(JSONObject().put("incomplete", true).put("code", "LOGGER_FAILED").toString()) }
                    val dead = generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException }
                    runCatching { program?.close(); engine?.let { SandboxEngine.release(it, dead) } }; program = null; engine = null
                } finally { synchronized(this) { queued-- } }
            }
        }
        private fun write(value: JSONObject, detail: String?, capture: Boolean) {
            if (program == null) {
                engine = SandboxEngine.acquire(context)
                program = SandboxProgram(engine!!, 512 * 1024)
                program!!.evaluate(SandboxBundle.build(context, listOf("logging-session.js"), "configureDealCapabilities([]);", "initializeLogger(factories)", assetRoot = "logging"))
            }
            value.put("sequence", ++sequence).put("time", System.currentTimeMillis().toString()).put("elapsed", SystemClock.elapsedRealtime().toString()).put("capture", capture)
            val bytes = detail?.toByteArray(Charsets.UTF_8)
            val artifact = if (bytes != null && capture) UUID.randomUUID().toString() else ""
            value.put("artifact", artifact)
            val admissionTruncated = value.optBoolean("admissionTruncated")
            value.remove("admissionTruncated")
            val normalized = JSONObject(program!!.evaluate("normalizeLog(${value})"))
            if (artifact.isNotEmpty()) {
                val limit = normalized.getInt("contentLimit")
                val stored = bytes!!.copyOfRange(0, minOf(bytes.size, limit))
                val files = directory.listFiles()?.filter { it.extension == "json" && it.name != "health.json" }?.sortedBy { it.lastModified() }.orEmpty()
                var used = files.sumOf { it.length() }
                for (file in files) { if (used + stored.size <= normalized.getInt("artifactStoreLimit")) break; used -= file.length(); file.delete() }
                File(directory, "$artifact.json").writeBytes(stored)
                normalized.put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) })
                if (stored.size != bytes.size || admissionTruncated) normalized.put("content", "truncated")
            }
            val eventStoreLimit = normalized.getInt("eventStoreLimit")
            queueLimit = normalized.getInt("queueLimit")
            normalized.remove("contentLimit"); normalized.remove("artifactStoreLimit"); normalized.remove("eventStoreLimit"); normalized.remove("queueLimit")
            val file = File(directory, "events.jsonl")
            if (file.length() + normalized.toString().toByteArray().size > eventStoreLimit) {
                val previous = File(directory, "previous.jsonl"); previous.delete(); if (file.exists()) check(file.renameTo(previous))
            }
            file.appendText(normalized.toString() + "\n")

        }
    }
}
