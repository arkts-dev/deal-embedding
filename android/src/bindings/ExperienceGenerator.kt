package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Generation diagnostics persist to app storage so a failure stays readable after logcat rolls. */
internal class GenerationTrace(private val context: android.content.Context) {
    private val file = java.io.File(context.filesDir, "generation-trace.txt")
    private val run = UUID.randomUUID().toString()
    private val started = android.os.SystemClock.elapsedRealtime()
    @Synchronized fun record(line: String) {
        if (!context.getSharedPreferences("embedding-debug", 0).getBoolean("captureReplay", false)) return
        if (file.length() > 512 * 1024) file.delete()
        runCatching { file.appendText("run=$run elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} " + line + "\n") }
            .onFailure { android.util.Log.e("Generation", "Cannot append generation trace", it) }
    }
}

class CheckedCandidate internal constructor(internal val source: ExperienceSource, internal val output: File, internal val revision: String, val attempts: Int, internal val receipts: StageReceipts, internal val identity: String = UUID.randomUUID().toString()) : AutoCloseable {
    internal var consumed = false
    override fun close() { if (!consumed) output.parentFile.deleteRecursively() }
}
internal fun catalogRevision(config: EmbeddingConfig): String = MessageDigest.getInstance("SHA-256").digest(JSONArray(config.contracts.map { it.json() }).toString().toByteArray()).joinToString("") { "%02x".format(it) }

/** Runs compiled trusted DEAL. No native retry loop, and no capability invocation during inference. */
internal class ExperienceGenerator(private val context: Context, private val config: EmbeddingConfig, private val engine: JavaScriptSandbox) {
    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit): CheckedCandidate {
        require(intent.isNotBlank() && intent.length <= 4096 && disclosedContext.length <= 8192)
        val revision = catalogRevision(config)
        val catalog = JSONArray(config.contracts.map { it.json() }).toString()
        val checked = mutableMapOf<String, Pair<ExperienceSource, File>>()
        val trace = GenerationTrace(context)
        val receipts = StageReceipts(context)
        receipts.event("request", "started")
        // Neutral boundary representation: DEAL currently cannot read a dynamic string table key.
        val disclosed = runCatching { JSONObject(disclosedContext) }.getOrElse { JSONObject() }
        val entries = JSONArray(disclosed.keys().asSequence().filter { disclosed.opt(it) is String }.map {
            JSONObject().put("key", it).put("value", disclosed.getString(it))
        }.toList())
        trace.record("--- generation start ---")
        val program = SandboxProgram(engine, 512 * 1024)
        fun eval(s: String) = program.evaluate(s)
        try {
            val bundle = SandboxBundle.build(context, listOf("generation-contracts.js", "generation-session.js"), "",
                "runGeneration(factories, ${JSONObject().put("intent", intent).put("context", disclosedContext).put("contextEntries", JSONObject().put("entries", entries).toString()).put("sourceName", config.sourceName).put("repairStrategy", config.repairStrategy)})", assetRoot = "generation")
            eval(bundle)
            var attempts = 0
            while (true) {
                cancellation.check()
                val snapshot = JSONObject(eval("generationSnapshot()"))
                if (snapshot.getBoolean("done")) {
                    check(snapshot.optString("error").isEmpty()) { snapshot.optString("error") }
                    val result = snapshot.getJSONObject("result")
                    val id = result.getString("candidateId")
                    cancellation.check()
                    receipts.event("route", if (result.getBoolean("usedFallback")) "source" else "template", candidate = id)
                    val candidate = checked.remove(id) ?: error("Generation rejected (fallback=${result.getBoolean("usedFallback")}, attempts=${result.getInt("attempts")}): ${result.optString("diagnostics").take(4000)}")
                    trace.record("accepted candidate=$id sourceCalls=$attempts usedFallback=${result.getBoolean("usedFallback")}")
                    receipts.event("candidate", "accepted", candidate = id)
                    receipts.event("request", "completed", candidate = id)
                    return CheckedCandidate(candidate.first, candidate.second, revision, result.getInt("attempts"), receipts, id)
                }
                val requests = JSONArray(eval("dealCapabilities.take()"))
                for (i in 0 until requests.length()) {
                    cancellation.check()
                    val request = requests.getJSONObject(i); val args = request.getJSONArray("args")
                    val value: Any = when(request.getString("module")) {
                        "embedding/observer" -> {
                            val code = args.getString(2).substringBefore(':').takeIf { it.matches(Regex("CHOICE_[A-Z_]+|INVALID_ENVELOPE")) } ?: ""
                            if (args.getString(0) == "check" && args.getString(1) == "rejected" && code == "INVALID_ENVELOPE") receipts.event("check", "rejected", code = code)
                            else if (args.getString(0) == "lower" && args.getString(1) in setOf("started", "completed", "failed")) receipts.event("lower", args.getString(1), code = code)
                            else receipts.event("policy", if (args.getString(1) == "issued") "issued" else "validated", code = code)
                            trace.record("policy ${args.getString(1)}: ${args.getString(2)}")
                            JSONObject.NULL
                        }
                        "embedding/discovery" -> receipts.stage("discovery") { catalog }
                        "embedding/policy-data" -> when (request.getString("function")) {
                            "lowercase" -> args.getString(0).lowercase()
                            "utf16Length" -> args.getString(0).length
                            "sha256" -> MessageDigest.getInstance("SHA-256").digest(args.getString(0).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                            "parse" -> {
                                try { JSONObject(args.getString(0)) }
                                catch (error: JSONException) {
                                    eval("dealCapabilities.deliver(${JSONArray().put(JSONObject().put("id", request.getInt("id")).put("ok", false).put("error", JSONObject().put("code", "INVALID_JSON").put("message", "Expected JSON object")))})")
                                    continue
                                }
                            }
                            else -> error("Unknown trusted data mechanism")
                        }
                        "embedding/chooser" -> when (request.getString("function")) {
                            "choose" -> {
                                progress("Choosing a workspace shape")
                                receipts.stage("choice", operation = request.getInt("id").toString()) {
                                    model.complete(args.getString(0), "", "", cancellation).also { trace.record("choice answer: " + it) }
                                }
                            }
                            else -> error("Unknown trusted chooser mechanism")
                        }
                        "embedding/model" -> {
                            attempts++
                            val reason = args.getString(2).substringBefore(':').takeIf { it.matches(Regex("CHOICE_[A-Z_]+")) } ?: ""
                            receipts.event("route", if (attempts == 1) "fallback" else "repair", code = reason)
                            progress("Generating source · attempt $attempts of 3")
                            val raw = receipts.stage("source", operation = request.getInt("id").toString()) {
                                model.complete(args.getString(0), args.getString(1), args.getString(2), cancellation)
                            }
                            trace.record("source attempt " + attempts + " response:\n" + raw)
                            raw
                        }
                        "embedding/checker" -> {
                            progress("Checking generated source")
                            val id = UUID.randomUUID().toString()
                            try {
                                val source = ExperienceSource(args.getString(0), args.getString(1))
                                trace.record("checking deal:\n" + source.deal)
                                trace.record("checking dealui:\n" + source.ui)
                                val output = receipts.stage("check", candidate = id) { ExperienceCompiler(context, config).compile(source.deal, source.ui) }
                                checked[id] = source to output
                                JSONObject().put("accepted", true).put("candidateId", id).put("diagnostics", "")
                            } catch (error: Exception) {
                                if (error !is CandidateRejected) throw error
                                val diagnostics = error.diagnostics
                                trace.record("checker rejected:\n" + diagnostics)
                                receipts.event("check", "rejected", candidate = id, code = "CANDIDATE_REJECTED")
                                JSONObject().put("accepted", false).put("candidateId", "").put("diagnostics", diagnostics)
                            }
                        }
                        else -> error("Untrusted generation request")
                    }
                    cancellation.check()
                    eval("dealCapabilities.deliver(${JSONArray().put(JSONObject().put("id", request.getInt("id")).put("ok", true).put("value", value))})")
                }
                if (requests.length() == 0) Thread.sleep(10)
            }
        } catch (error: Throwable) {
            receipts.event("request", "failed", code = error.javaClass.simpleName)
            trace.record("generation stopped: " + android.util.Log.getStackTraceString(error))
            throw error
        } finally { program.close(); checked.values.forEach { it.second.parentFile.deleteRecursively() } }
    }
}
