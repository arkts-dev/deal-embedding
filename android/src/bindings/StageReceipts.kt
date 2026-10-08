package dev.deal.embedding

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Bounded shape-only diagnostics. Never pass prompts, source, payloads or exception messages. */
internal class StageReceipts(private val context: Context, val run: String = UUID.randomUUID().toString()) {
    fun event(stage: String, outcome: String, candidate: String = "", workspace: String = "",
              operation: String = "", code: String = "", durationMs: Long = 0, version: Int = -1, parentOperation: String = "") {
        val value = JSONObject().put("run", run).put("stage", stage).put("outcome", outcome)
            .put("candidate", candidate).put("workspace", workspace).put("operation", operation)
            .put("code", code).put("durationMs", durationMs).put("version", version).put("parentOperation", parentOperation)
        synchronized(lock) {
            try {
                val file = File(context.filesDir, "embedding-receipts.jsonl")
                if (file.length() + value.toString().toByteArray().size > 256 * 1024) {
                    val previous = File(context.filesDir, "embedding-receipts.previous.jsonl")
                    previous.delete()
                    check(file.renameTo(previous)) { "Receipt rotation failed" }
                }
                file.appendText(value.toString() + "\n")
            } catch (error: Exception) { android.util.Log.e("EmbeddingReceipts", "Receipt persistence failed", error) }
        }
    }
    fun <T> stage(name: String, candidate: String = "", workspace: String = "", operation: String = "", block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        event(name, "started", candidate, workspace, operation)
        try {
            return block().also { event(name, "completed", candidate, workspace, operation, durationMs = SystemClock.elapsedRealtime() - start) }
        } catch (error: Throwable) {
            val code = when (error) {
                is CandidateRejected -> "CANDIDATE_REJECTED"
                is java.util.concurrent.CancellationException -> "CANCELLED"
                else -> error.javaClass.simpleName
            }
            event(name, "failed", candidate, workspace, operation, code, SystemClock.elapsedRealtime() - start)
            throw error
        }
    }
    companion object { private val lock = Any() }
}
