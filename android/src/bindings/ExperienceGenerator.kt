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
    enum class Reason { ATTEMPT_LIMIT }
}
internal class CheckedCandidate internal constructor(private val context:Context, internal val source: ExperienceSource, internal val output: File, internal val revision: String, val attempts: Int, internal val log: EmbeddingLog, internal val identity: String = UUID.randomUUID().toString(), val origin: WorkspaceOrigin = WorkspaceOrigin.SAVED_SOURCE) : AutoCloseable {
    internal var consumed = false
    override fun close() { if (!consumed) StagingReclamation.remove(context,output.parentFile) }
}
internal fun canonicalCatalog(config: RuntimeConfig): String {
    fun encode(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key -> JSONObject.quote(key)+":"+encode(value.get(key)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { encode(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    return encode(JSONArray(config.contracts.map { it.json() }))
}
internal fun catalogRevision(config: RuntimeConfig): String = MessageDigest.getInstance("SHA-256").digest(canonicalCatalog(config).toByteArray()).joinToString("") { "%02x".format(it) }

/** Transport/compiler mechanisms for the sole compiled DEAL generation entry. No retries or composition policy. */
internal class ExperienceGenerator(private val context: Context, private val config: RuntimeConfig, private val engine: JavaScriptSandbox, private val capabilityHost: dev.deal.embedding.capabilities.CapabilityHost) {
    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit, log: EmbeddingLog = EmbeddingLog(context), contextId: String = UUID.randomUUID().toString(), contextSchema: JSONObject = WireType("record").json()): CheckedCandidate {
        require(intent.isNotBlank())
        val revision=catalogRevision(config)
        val checked=CandidateChecks(context,config,engine,log)
        val program=SandboxProgram(engine,512*1024,config.execution)
        var failure:Throwable?=null; var accepted:CheckedCandidate?=null
        fun deliver(request:JSONObject,value:Any)=program.evaluate("dealCapabilities.deliver(${JSONArray().put(JSONObject().put("id",request.getInt("id")).put("ok",true).put("value",value))})")
        fun reject(request:JSONObject,code:String,message:String)=program.evaluate("dealCapabilities.deliver(${JSONArray().put(JSONObject().put("id",request.getInt("id")).put("ok",false).put("error",JSONObject().put("code",code).put("message",message)))})")
        try {
            log.event("request","started",detail=JSONObject().put("intent",intent).put("context",disclosedContext).put("catalog",canonicalCatalog(config)).toString())
            val input=JSONObject().put("intent",intent).put("context",JSONObject().put("id",contextId)).put("capabilities",JSONArray(config.contracts.map{it.json()}))
            val configure="protocolContexts[${JSONObject.quote(contextId)}]={value:${JSONObject.quote(disclosedContext)},schema:$contextSchema};"
            val bundle=log.stage("generation-bundle"){SandboxBundle.build(context,listOf("generation-contracts.js","protocol-contracts.js","protocol-host.js","generation-session.js"),configure,"runGeneration(factories, $input)",assetRoot=config.generationAssets)}
            log.stage("generation-initialize"){program.evaluate(bundle)}
            while(true){
                cancellation.check()
                val snapshot=JSONObject(program.evaluateString("generationSnapshot()"))
                if(snapshot.getBoolean("done")){
                    snapshot.optJSONObject("error")?.let{throw ProtocolFailure(it.getString("code"),it.getString("message"))}
                    val result=snapshot.getJSONObject("result"); val id=result.getString("candidateId")
                    cancellation.check()
                    if(id.isEmpty())throw GenerationRejected(result.getInt("attempts"),GenerationRejected.Reason.ATTEMPT_LIMIT,result.getString("diagnostics"))
                    val candidate=checked.take(id)
                    log.event("request","completed",candidate=id)
                    return CheckedCandidate(context,candidate.first,candidate.second,revision,result.getInt("attempts"),log,id,WorkspaceOrigin.AI_SOURCE).also{accepted=it}
                }
                val requests=JSONArray(program.evaluateString("dealCapabilities.take()"))
                for(i in 0 until requests.length()){
                    cancellation.check()
                    val request=requests.getJSONObject(i);val args=request.getJSONArray("args");val function=request.getString("function")
                    val value:Any=when(request.getString("module")){
                        "embedding/construction-identity"->{check(function=="issue");UUID.randomUUID().toString()}
                        "embedding/inference"->{
                            check(function=="dispatch");val raw=args.getJSONObject(0)
                            val inference=InferenceRequest(raw.getString("input"),raw.getString("previous"),raw.getString("diagnostics"),raw.getString("operation"),raw.getString("stage"),raw.getInt("ask"),raw.getInt("maxTokens"),raw.getBoolean("thinking"))
                            progress("Constructing tool · ${inference.stage}")
                            val response=log.operation("inference",operation=inference.operation,target=inference.stage,detail=raw.toString()){child->model.dispatch(inference,cancellation,child)}
                            JSONObject().put("content",response.content).put("finish",response.finish).put("refusal",response.refusal)
                        }
                        "embedding/fragment-syntax"->{
                            try{FragmentMechanisms.invoke(function,args.getString(0))}
                            catch(error:deal.ui.UiDiagnostic){reject(request,"FRAGMENT_SYNTAX",error.message ?: "Invalid fragment view syntax");continue}
                            catch(error:IllegalArgumentException){reject(request,"FRAGMENT_SYNTAX",error.message ?: "Invalid fragment syntax");continue}
                        }
                        "embedding/questions"->{
                            // No host UI callback is registered: unanswered is explicitly resolved to the issued default by DEAL.
                            check(function=="ask");JSONObject().put("optionId",JSONObject.NULL).put("text",JSONObject.NULL).put("cancelled",false)
                        }
                        "embedding/policy-data"->when(function){
                            "catalogJson"->args.getJSONArray(0).toString()
                            "capabilityDeclarations"->{val modules=args.getJSONArray(0);dev.deal.embedding.capabilities.CapabilityBindings.declarations((0 until modules.length()).map{index->CapabilityContract.parse(modules.getJSONObject(index).toString())})}
                            "sha256"->MessageDigest.getInstance("SHA-256").digest(args.getString(0).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                            else->error("Unissued generation data mechanism: $function")
                        }
                        "embedding/checker"->{
                            when(function) {
                                "discard"->{checked.discard(args.getString(0));JSONObject.NULL}
                                "finish"->{checked.finish(args.getString(0));JSONObject.NULL}
                                "step"->checked.step(args.getString(0),args.getString(1),args.getInt(2),args.getString(3))
                                "compile"->{
                                    progress("Checking constructed source")
                                    checked.compile(ExperienceSource(args.getString(0),args.getString(1)),null)
                                }
                                else->error("Unissued checking mechanism")
                            }
                        }
                        else->error("Unissued generation mechanism: ${request.getString("module")}/$function")
                    }
                    cancellation.check();deliver(request,value)
                }
                if(requests.length()==0)Thread.sleep(10)
            }
        }catch(error:Throwable){failure=error;throw error}
        finally{
            var cleanup:Throwable?=null
            fun clean(block:()->Unit){try{block()}catch(error:Throwable){if(cleanup==null)cleanup=error else cleanup!!.addSuppressed(error)}}
            log.event("generation-bridge","completed",target=program.metrics().toString());clean{program.close()}
            clean{checked.close()}
            cleanup?.let{error->accepted?.let{clean{it.close()}};if(failure!=null)failure!!.addSuppressed(error)else throw error}
        }
    }
}
