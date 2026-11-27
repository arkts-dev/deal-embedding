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
class EmbeddingLog private constructor(private val context: Context, val run: String, private val defaultParent: String, private val execution: ExecutionLimits = ExecutionLimits()) {
    constructor(context: Context) : this(context.applicationContext, UUID.randomUUID().toString(), "")
    constructor(context: Context, execution: ExecutionLimits) : this(context.applicationContext, UUID.randomUUID().toString(), "", execution)
    private fun child(parent: String) = EmbeddingLog(context, run, parent, execution).also{it.boundExperience=boundExperience;it.boundSession=boundSession}
    internal fun bind(workspace: String,session:String) = EmbeddingLog(context,run,defaultParent,execution).also { it.boundExperience=workspace;it.boundSession=session }
    internal val experienceIdentity: String get() = boundExperience
    private var boundExperience=""
    private var boundSession=""
    val capturesContent: Boolean get() = runCatching { context.getSharedPreferences("embedding-logging", 0).getBoolean("content", false) }.getOrDefault(false)
    fun event(stage: String, outcome: String, candidate: String = "", workspace: String = "", operation: String = "", code: String = "", durationMs: Long = 0, version: Int = -1, parentOperation: String = "", target: String = "", detail: String? = null, span: String = UUID.randomUUID().toString()) {
        try {
            sink(context).submit(JSONObject().put("trace", run).put("span", span).put("parent", parentOperation.ifEmpty { defaultParent })
                .put("stage", stage).put("outcome", outcome).put("candidate", candidate).put("workspace", workspace.ifEmpty{boundExperience}).put("operation", operation).put("session",boundSession)
                .put("code", code).put("durationMs", durationMs.coerceIn(0, Int.MAX_VALUE.toLong())).put("version", version).put("target", target), detail, capturesContent, execution)
        } catch (error: Exception) { emergency(context, error) }
    }
    internal fun <T> stage(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: () -> T): T = operation(name, candidate, workspace, operation, target, detail) { block() }
    internal fun <T> operation(name: String, candidate: String = "", workspace: String = "", operation: String = "", target: String = "", detail: String? = null, block: (EmbeddingLog) -> T): T {
        val span = UUID.randomUUID().toString(); val start = SystemClock.elapsedRealtime()
        event(name, "started", candidate, workspace, operation, target = target, detail = detail, span = span)
        try { return block(child(span)).also { event(name, "completed", candidate, workspace, operation, durationMs = SystemClock.elapsedRealtime() - start, target = target, span = span) } }
        catch (error: Throwable) {
            val code = when (error) { is CandidateRejected -> "CANDIDATE_REJECTED"; is deal.ui.UiDiagnostic -> error.code(); is ProtocolFailure -> error.code; is java.util.concurrent.CancellationException -> "CANCELLED"; else -> error.javaClass.simpleName }
            event(name, "failed", candidate, workspace, operation, code, SystemClock.elapsedRealtime() - start, target = target, detail = if (error is CandidateRejected) error.diagnostics else null, span = span)
            throw error
        }
    }
    companion object {
        internal fun traced(context:Context,trace:String)=EmbeddingLog(context.applicationContext,trace,"")
        internal fun traced(context:Context,trace:String,execution:ExecutionLimits)=EmbeddingLog(context.applicationContext,trace,"",execution)
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
        private var programLimits: ExecutionLimits? = null
        private var sequence = 0
        private val directory = directory(context).apply { mkdirs() }
        // The artifact store and the loss record are the deterministic retained store; this sink owns
        // only the sandbox that shapes a record and the queue in front of it.
        private val store = ObservationStore(directory)
        private var loss = store.health()
        private var emergencyReported = false
        fun resetHealth() { loss = RetainedHealth(); emergencyReported = false }
        private fun record(value: RetainedHealth, extra: String, now: Long) {
            loss = value
            val snapshot = JSONObject().put("incomplete", value.incomplete).put("active", value.active).put("code", value.code).put("lost", value.lost).put(extra, now.toString())
            runCatching { File(directory, "health.json").writeText(snapshot.toString()) }
        }
        private fun fault(code: String, count: Int) { record(loss.fault(code, count), "time", System.currentTimeMillis()) }
        private fun recovered() {
            emergencyReported = false
            if (loss.active) { record(loss.recovered(), "recoveredAt", System.currentTimeMillis()) }
        }
        @Synchronized fun submit(value: JSONObject, detail: String?, capture: Boolean, limits: ExecutionLimits) {
            if (queued >= queueLimit) { dropped++; return }
            // Timestamp production/admission, not asynchronous persistence.
            value.put("time",System.currentTimeMillis().toString()).put("elapsed",SystemClock.elapsedRealtime().toString())
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
                        write(JSONObject().put("trace", "logger").put("span", UUID.randomUUID().toString()).put("parent", "")
                            .put("stage", "logger").put("outcome", "dropped").put("code", "QUEUE_LIMIT").put("bytes", lost)
                            .put("candidate", "").put("workspace", "").put("session", "").put("operation", "")
                            .put("target", "").put("durationMs", 0).put("version", -1), null, false, false, limits)
                    }
                    write(value, retained, capture, truncated, limits)
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
        private fun write(value: JSONObject, bytes: ByteArray?, capture: Boolean, truncated: Boolean, limits: ExecutionLimits) {
            if (program == null || programLimits != limits) {
                // A host that changes its configured block gets an isolate built from the new block; the
                // connected sandbox engine is reused and only the isolate is replaced.
                runCatching { program?.close() }
                val active = engine ?: SandboxEngine.acquire(context).also { engine = it }
                program = SandboxProgram(active, 512 * 1024, limits)
                programLimits = limits
                program!!.evaluate(SandboxBundle.build(context, listOf("logging-session.js"), "configureDealCapabilities([]);", "initializeLogger(factories)", assetRoot = "logging"))
            }
            value.put("sequence", ++sequence).put("capture", capture)
            if(!value.has("time"))value.put("time",System.currentTimeMillis().toString()).put("elapsed",SystemClock.elapsedRealtime().toString())
            val artifact = if (bytes != null && capture) UUID.randomUUID().toString() else ""
            value.put("artifact", artifact)
            val normalized = JSONObject(program!!.evaluate("normalizeLog(${value})"))
            if (artifact.isNotEmpty()) {
                val stored = bytes!!
                check(stored.size <= normalized.getInt("contentLimit"))
                // The retained store reclaims the oldest artifacts until this capture fits, so a codec
                // bound and the store bound can never disagree about what was written.
                store.store(stored, artifact, truncated, normalized.getInt("artifactStoreLimit"))
                normalized.put("sha256", ObservationStore.digest(stored))
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
