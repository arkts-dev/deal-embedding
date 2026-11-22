package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException

/** Request-local staging and isolated execution mechanisms. DEAL owns admission and scripts. */
internal class CandidateChecks(private val context:Context,private val config:RuntimeConfig,private val engine:JavaScriptSandbox,private val log:EmbeddingLog):AutoCloseable {
    private class Candidate(val source:ExperienceSource,val output:File,var isolate:SandboxProgram?=null,val restored:String?=null,val ownsOutput:Boolean=true)
    private val candidates=mutableMapOf<String,Candidate>()
    /** The private host configuration supplies the target, not checker arguments or source. */
    fun compile(source:ExperienceSource):JSONObject {
        return try {
            val output=ExperienceCompiler(context,config,log).compile(source.deal,source.ui,config.target)
            val id=UUID.randomUUID().toString();candidates[id]=Candidate(source,output)
            JSONObject().put("accepted",true).put("candidateId",id).put("diagnostics","").put("manifest",JSONObject(File(output,"admission.json").readText()))
        } catch(error:CandidateRejected) {
            if(error.suppressed.isNotEmpty())throw error
            JSONObject().put("accepted",false).put("candidateId","").put("diagnostics",error.diagnostics)
        }
    }
    fun step(id:String,operation:String,index:Int,payload:String):JSONObject {
        val candidate=candidates[id] ?: error("Unissued candidate")
        if(operation=="start") {
            check(candidate.isolate==null)
            candidate.isolate=SandboxProgram(engine,256*1024,config.execution)
        }
        val isolate=checkNotNull(candidate.isolate)
        return try {
            if(operation=="start")isolate.evaluate(SandboxBundle.build(context,listOf("checking-session.js"),dev.deal.embedding.capabilities.CapabilityBindings.script(config.contracts),"openChecking(factories,\"$ENTRY_NAME.js\",${DealSessionBindings.record(candidate.restored)},${DealSessionBindings.appModule(config)?.let { JSONObject.quote(it) } ?: "null"})",output=candidate.output))
            JSONObject(isolate.evaluateString("checkingStep(${JSONObject.quote(operation)},$index,JSON.parse(${JSONObject.quote(payload)}))")).also { report ->
                if (!report.getBoolean("ok")) report.put("diagnostics", report.getString("diagnostics").replace(candidate.output.parentFile!!.absolutePath + "/", ""))
                if(report.getBoolean("ok")) {
                    fun render(node:JSONObject){
                        if(node.getString("component").substringAfterLast('.')=="Surface") {
                            val props=node.getJSONArray("props")
                            val shapes=(0 until props.length()).map{props.getJSONObject(it)}.single{it.getString("name")=="shapes"}.getString("stringValue")
                            val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
                            try{paintShapes(android.graphics.Canvas(bitmap),org.json.JSONArray(shapes),0,intArrayOf(0))}finally{bitmap.recycle()}
                        }
                        val children=node.getJSONArray("children");for(i in 0 until children.length())render(children.getJSONObject(i))
                    }
                    try{render(JSONObject(report.getString("tree")))}catch(error:IllegalArgumentException){report.put("ok",false).put("diagnostics","CHECK_RENDER: invalid graphics geometry")}
                }
            }
        } catch(error:TimeoutException) {
            // A bounded generated evaluation ran away. Service death, OOM, I/O and
            // cancellation are not execution verdicts and propagate unchanged.
            if(error.suppressed.isNotEmpty())throw error
            JSONObject().put("ok",false).put("diagnostics","CHECK_RUNAWAY: isolated evaluation deadline").put("tree","").put("handlers",0).put("providerCalls",0)
        }
    }
    fun discard(id:String) {
        val candidate=candidates[id] ?: error("Unissued candidate")
        var failure:Throwable?=null
        try{candidate.isolate?.close();candidate.isolate=null}catch(error:Throwable){failure=error}
        try{if(candidate.ownsOutput)StagingReclamation.remove(context,candidate.output.parentFile)}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        if(failure==null)candidates.remove(id) else throw failure
    }
    fun finish(id:String) { val candidate=candidates[id] ?: error("Unissued candidate");candidate.isolate?.close();candidate.isolate=null }
    fun take(id:String):Pair<ExperienceSource,File> {
        val candidate=candidates[id] ?: error("Unissued candidate")
        candidate.isolate?.close();candidate.isolate=null
        candidates.remove(id)
        return candidate.source to candidate.output
    }
    /** Execute the compiled admission policy for saved/supplied source, without a model. */
    fun prepare(source:ExperienceSource,output:File,restored:String?) {
        val id=UUID.randomUUID().toString()
        candidates[id]=Candidate(source,output,restored=restored,ownsOutput=false)
        var failure:Throwable?=null
        try{execute(source,id)}catch(error:Throwable){failure=error;throw error}
        finally{try{discard(id)}catch(error:Throwable){if(failure==null)throw error else failure.addSuppressed(error)}}
    }
    fun check(source:ExperienceSource):File = execute(source,null)
    private fun execute(source:ExperienceSource,preparedId:String?):File {
        val policy=SandboxProgram(engine,256*1024,config.execution)
        var failure:Throwable?=null
        try {
            policy.evaluate(SandboxBundle.build(context,listOf("generation-contracts.js","protocol-contracts.js","protocol-host.js","checking-policy.js"),"","startCheckingPolicy(factories,${JSONObject().put("deal",source.deal).put("dealui",source.ui)},${preparedId?.let(JSONObject::quote) ?: "null"})",assetRoot=config.generationAssets))
            while(true) {
                val snapshot=JSONObject(policy.evaluateString("checkingPolicySnapshot()"))
                if(snapshot.getBoolean("done")) {
                    snapshot.optJSONObject("error")?.let{throw ProtocolFailure(it.getString("code"),it.getString("message"))}
                    val result=snapshot.getJSONObject("result")
                    if(!result.getBoolean("accepted"))throw CandidateRejected(result.getString("diagnostics"))
                    return if(preparedId==null)take(result.getString("candidateId")).second else checkNotNull(candidates[preparedId]).output
                }
                val requests=org.json.JSONArray(policy.evaluateString("dealCapabilities.take()"))
                for(i in 0 until requests.length()) {
                    val request=requests.getJSONObject(i);check(request.getString("module")=="embedding/checker")
                    val args=request.getJSONArray("args")
                    val value:Any=when(request.getString("function")) {
                        "compile"->compile(ExperienceSource(args.getString(0),args.getString(1)))
                        "step"->step(args.getString(0),args.getString(1),args.getInt(2),args.getString(3))
                        "discard"->{discard(args.getString(0));JSONObject.NULL}
                        "finish"->{finish(args.getString(0));JSONObject.NULL}
                        else->error("Unissued checking mechanism")
                    }
                    policy.evaluate("dealCapabilities.deliver(${org.json.JSONArray().put(JSONObject().put("id",request.getInt("id")).put("ok",true).put("value",value))})")
                }
                if(requests.length()==0)Thread.sleep(1)
            }
        } catch(error:Throwable){failure=error;throw error}
        finally {try{policy.close()}catch(error:Throwable){if(failure==null)throw error else failure.addSuppressed(error)}}
    }
    override fun close() {
        var failure:Throwable?=null
        for(id in candidates.keys.toList())try{discard(id)}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        failure?.let{throw it}
    }
}
