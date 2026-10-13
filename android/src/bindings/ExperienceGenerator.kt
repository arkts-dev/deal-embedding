package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Host-facing facts, never inferred from generated source or its title. */
enum class WorkspaceOrigin { AI_SOURCE, CATALOGUE, SAVED_SOURCE }
class GenerationRejected(val attempts: Int, val reason: Reason, val diagnostics: String) : IllegalStateException("Generation rejected ($reason, attempts=$attempts): ${diagnostics.take(4000)}") {
    enum class Reason { ATTEMPT_LIMIT, REPEATED_RESPONSE, TEMPLATE_CHECK }
}
internal class CheckedCandidate internal constructor(private val context:Context, internal val source: ExperienceSource, internal val output: File, internal val revision: String, val attempts: Int, internal val log: EmbeddingLog, internal val identity: String = UUID.randomUUID().toString(), val origin: WorkspaceOrigin = WorkspaceOrigin.SAVED_SOURCE) : AutoCloseable {
    internal var consumed = false
    override fun close() { if (!consumed) StagingReclamation.remove(context,output.parentFile) }
}
internal fun catalogRevision(config: EmbeddingConfig): String = MessageDigest.getInstance("SHA-256").digest(JSONArray(config.contracts.map { it.json() }).toString().toByteArray()).joinToString("") { "%02x".format(it) }

/** Runs compiled trusted DEAL. No native retry loop, and no capability invocation during inference. */
internal class ExperienceGenerator(private val context: Context, private val config: EmbeddingConfig, private val engine: JavaScriptSandbox) {
    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit, log: EmbeddingLog = EmbeddingLog(context), contextId: String = UUID.randomUUID().toString(), contextSchema: JSONObject = WireType("record").json()): CheckedCandidate {
        require(intent.isNotBlank() && intent.length <= 4096 && disclosedContext.length <= 8192)
        cancellation.check()
        val revision = catalogRevision(config)
        val catalog = JSONArray(config.contracts.map { it.json() }).toString()
        val checked = mutableMapOf<String, Pair<ExperienceSource, File>>()
        log.event("request", "started", detail = JSONObject().put("intent", intent).put("context", disclosedContext).put("catalog", catalog).toString())
        val program = SandboxProgram(engine, 512 * 1024)
        var originalFailure:Throwable?=null
        var accepted:CheckedCandidate?=null
        fun eval(s: String) = program.evaluate(s)
        try {
            val input = JSONObject().put("intent",intent).put("context",JSONObject().put("id",contextId)).put("capabilities",JSONArray(config.contracts.map { it.protocolJson() })).put("repairStrategy",config.repairStrategy)
            val configure = "protocolContexts[${JSONObject.quote(contextId)}]={value:${JSONObject.quote(disclosedContext)},schema:$contextSchema};"
            val bundle = SandboxBundle.build(context, listOf("generation-contracts.js", "protocol-contracts.js", "protocol-host.js", "generation-session.js"), configure,
                "runGeneration(factories, $input)", assetRoot = config.generationAssets)
            val extra = if(config.policyAssets.isNotEmpty()) SandboxBundle.factories(context,config.policyAssets) else "factories['app-policy.js']=function(require,module,exports){module.exports={offer:{\$f:async()=>loadedPolicyRuntime.makeClass('@\$external/catalogue-policy/CatalogueOffer','@\$external/catalogue-policy/CatalogueOffer',()=>({available:false,questions:[],stateJson:''}),{})}};};"
            eval(bundle.replace("return runGeneration(factories,", extra+"\nreturn runGeneration(factories,"))
            var attempts = 0
            while (true) {
                cancellation.check()
                val snapshot = JSONObject(eval("generationSnapshot()"))
                if (snapshot.getBoolean("done")) {
                    if(snapshot.optJSONObject("error")!=null){val e=snapshot.getJSONObject("error");throw ProtocolFailure(e.getString("code"),e.getString("message"))}
                    val result = snapshot.getJSONObject("result")
                    val id = result.getString("candidateId")
                    cancellation.check()
                    log.event("route", if (result.getBoolean("usedFallback")) "source" else "template", candidate = id)
                    val candidate = checked.remove(id) ?: run {
                        val diagnostics = result.optString("diagnostics")
                        val reason = when {
                            !result.getBoolean("usedFallback") -> GenerationRejected.Reason.TEMPLATE_CHECK
                            diagnostics.startsWith("REPEATED_CANDIDATE:") -> GenerationRejected.Reason.REPEATED_RESPONSE
                            else -> GenerationRejected.Reason.ATTEMPT_LIMIT
                        }
                        throw GenerationRejected(result.getInt("attempts"), reason, diagnostics)
                    }
                    log.event("candidate", "accepted", candidate = id)
                    log.event("request", "completed", candidate = id)
                    return CheckedCandidate(context,candidate.first, candidate.second, revision, result.getInt("attempts"), log, id, if (result.getBoolean("usedFallback")) WorkspaceOrigin.AI_SOURCE else WorkspaceOrigin.CATALOGUE).also{accepted=it}
                }
                val requests = JSONArray(eval("dealCapabilities.take()"))
                for (i in 0 until requests.length()) {
                    cancellation.check()
                    val request = requests.getJSONObject(i); val args = request.getJSONArray("args")
                    val value: Any = when(request.getString("module")) {
                        "embedding/observer" -> {
                            val code = args.getString(2).substringBefore(':').takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) } ?: ""
                            if (args.getString(0) == "check" && args.getString(1) == "rejected" && code == "INVALID_ENVELOPE") log.event("check", "rejected", code = code)
                            else if (args.getString(0) == "lower" && args.getString(1) in setOf("started", "completed", "failed")) log.event("lower", args.getString(1), code = code)
                            else log.event("policy", if (args.getString(1) == "issued") "issued" else "validated", code = code)
                            JSONObject.NULL
                        }
                        "embedding/discovery" -> log.stage("discovery") { catalog }
                        "embedding/protocol" -> when(request.getString("function")) {
                            "questions" -> ChoiceProtocol.questions(args.getJSONArray(0)).toString()
                            "answers" -> ChoiceProtocol.answers(args.getString(0),args.getJSONArray(1))
                            "envelope" -> args.getJSONObject(0).toString()
                            else -> error("Unknown protocol mechanism")
                        }
                        "embedding/policy-data" -> when (request.getString("function")) {
                            "catalogJson" -> protocolCatalog(args.getJSONArray(0)).toString()
                            "contextEntries" -> { val v=JSONObject(args.getString(0));JSONObject().put("entries",JSONArray(v.keys().asSequence().filter{v.opt(it) is String}.map{JSONObject().put("key",it).put("value",v.getString(it))}.toList())).toString() }
                            "member" -> args.getJSONObject(0).getJSONObject(args.getString(1))
                            "stringMember" -> args.getJSONObject(0).getString(args.getString(1))
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
                                progress("Finding a suitable catalogue template…")
                                log.operation("choice", operation = request.getInt("id").toString(), detail = args.toString()) { child ->
                                    model.complete(args.getString(0), "", "", cancellation, child).also { child.event("choice", "response", detail = it) }
                                }
                            }
                            else -> error("Unknown trusted chooser mechanism")
                        }
                        "embedding/model" -> {
                            attempts++
                            val reason = args.getString(2).substringBefore(':').takeIf { it.matches(Regex("CHOICE_[A-Z_]+")) } ?: ""
                            log.event("route", if (attempts == 1) "fallback" else "repair", code = reason)
                            progress("AI writing logic and screen · attempt $attempts of 3")
                            val raw = log.operation("source", operation = request.getInt("id").toString(), detail = args.toString()) { child ->
                                model.complete(args.getString(0), args.getString(1), args.getString(2), cancellation, child).also { child.event("source", "response", detail = it) }
                            }
                            raw
                        }
                        "embedding/checker" -> {
                            progress(if (attempts == 0) "Checking catalogue workspace…" else "Checking AI-written code · attempt $attempts of 3")
                            val id = UUID.randomUUID().toString()
                            try {
                                val source = ExperienceSource(args.getString(0), args.getString(1))
                                val output = log.operation("check", candidate = id, detail = JSONObject().put("deal", source.deal).put("dealui", source.ui).toString()) { child -> ExperienceCompiler(context, config, child).compile(source.deal, source.ui) }
                                checked[id] = source to output
                                JSONObject().put("accepted", true).put("candidateId", id).put("diagnostics", "")
                            } catch (error: Exception) {
                                if (error !is CandidateRejected) throw error
                                val diagnostics = error.diagnostics
                                log.event("check", "rejected", candidate = id, code = "CANDIDATE_REJECTED")
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
            originalFailure=error
            log.event("request", "failed", code = error.javaClass.simpleName)
            throw error
        } finally {
            var cleanup:Throwable?=null
            fun clean(block:()->Unit){try{block()}catch(error:Throwable){if(cleanup==null)cleanup=error else cleanup!!.addSuppressed(error)}}
            clean{program.close()}
            checked.values.forEach { clean{StagingReclamation.remove(context,it.second.parentFile)} }
            cleanup?.let{error->
                accepted?.let{candidate->runCatching{candidate.close()}.onFailure{error.addSuppressed(it)}}
                if(originalFailure!=null)originalFailure!!.addSuppressed(error)else throw error
            }
        }
    }
}
