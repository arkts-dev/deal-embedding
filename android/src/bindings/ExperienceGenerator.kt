package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class CheckedCandidate internal constructor(internal val source: ExperienceSource, internal val output: File, internal val revision: String, val attempts: Int) : AutoCloseable {
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
        val program = SandboxProgram(engine, 512 * 1024)
        fun eval(s: String) = program.evaluate(s)
        try {
            val bundle = SandboxBundle.build(context, listOf("generation-contracts.js", "generation-session.js"), "",
                "runGeneration(factories, ${JSONObject().put("intent", intent).put("context", disclosedContext)})", assetRoot = "generation")
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
                    val candidate = checked.remove(id) ?: error("Generation exhausted repairs: ${result.optString("diagnostics").take(4000)}")
                    return CheckedCandidate(candidate.first, candidate.second, revision, result.getInt("attempts"))
                }
                val requests = JSONArray(eval("dealCapabilities.take()"))
                for (i in 0 until requests.length()) {
                    cancellation.check()
                    val request = requests.getJSONObject(i); val args = request.getJSONArray("args")
                    val value: Any = when(request.getString("module")) {
                        "embedding/discovery" -> catalog
                        "embedding/model" -> { attempts++; progress("Generating source · attempt $attempts of 3"); model.complete(args.getString(0), args.getString(1), args.getString(2), cancellation) }
                        "embedding/checker" -> {
                            progress("Checking generated source")
                            try {
                                val raw = args.getString(0)
                                val envelope = runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Envelope is not a JSON object") }
                                require(envelope.length() == 2) { "Envelope must contain exactly deal and dealui; found " + envelope.keys().asSequence().sorted().joinToString(", ") }
                                val source = ExperienceSource(envelope.getString("deal"), envelope.getString("dealui"))
                                val output = ExperienceCompiler(context, config).compile(source.deal, source.ui)
                                val id = UUID.randomUUID().toString(); checked[id] = source to output
                                JSONObject().put("accepted", true).put("candidateId", id).put("diagnostics", "")
                            } catch (error: Exception) {
                                val diagnostics = if (error is CandidateRejected) error.diagnostics else JSONArray().put(JSONObject().put("stage", "envelope-or-host").put("code", "INVALID_CANDIDATE").put("message", error.message?.take(1000))).toString()
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
        } finally { program.close(); checked.values.forEach { it.second.parentFile.deleteRecursively() } }
    }
}
