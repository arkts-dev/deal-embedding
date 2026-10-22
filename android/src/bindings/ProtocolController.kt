package dev.deal.embedding

import android.content.Context
import org.json.*
import java.util.UUID
import java.util.concurrent.*

open class ProtocolFailure(val code:String, message:String):IllegalStateException(message)

/** Native bootstrap for a trusted compiled DEAL consumer. Never installed in a UI isolate.
 * The controller owns synchronous state; workers implement asynchronous mechanisms.
 * The evaluation pump never waits for a mechanism to finish, so controls remain serviceable.
 */
class ProtocolController(private val context:Context, private val embedding:Embedding, assets:String="protocol-consumer", consumer:String="consumer.js"):AutoCloseable {
    private val engine=SandboxEngine.acquire(context)
    private val program=SandboxProgram(engine,1024*1024)
    private val workers=Executors.newCachedThreadPool()
    private val serial=Executors.newSingleThreadExecutor()
    private val driving=Executors.newSingleThreadScheduledExecutor()
    private val completed=ConcurrentLinkedQueue<JSONObject>()
    private val requests=ConcurrentHashMap<String,GenerationRequest>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val futures=ConcurrentHashMap<String,CompletableFuture<Any>>()
    private var sequence=0
    private val generatedTraces=ConcurrentHashMap<String,String>()
    @Volatile private var ended=false
    @Volatile private var shutdownRequested=false
    private val nextWaits=ConcurrentHashMap<Int,CompletableFuture<SessionUpdate>>()
    private val releases=linkedMapOf<String,Experience>()
    private var reclamationFailure:Throwable?=null
    @Synchronized internal fun pendingReleases():Int=releases.size
    @Synchronized fun flushReclamation(){
        advance();drainReleases(true)
        if(releases.isNotEmpty())throw ProtocolFailure("CLEANUP_FAILED","Preparation reclamation remains pending").also{reclamationFailure?.let(it::addSuppressed)}
    }
    private var reclaimAfter=0L
    private fun drainReleases(force:Boolean=false){
        val now=System.nanoTime();if(!force&&now<reclaimAfter)return
        reclaimAfter=now+TimeUnit.SECONDS.toNanos(1)
        for((id,e) in releases.toMap())try{embedding.release(e);releases.remove(id)}catch(error:Throwable){reclamationFailure=error;embedding.controlFailure("release",error)}
    }
    init {
        try {
            val saved=embedding.saved().map{e->JSONObject().put("experience",experience(e)).put("source",source(embedding.source(e)))}
            val bootstrap=JSONObject().put("seed",UUID.randomUUID().toString()).put("saved",JSONArray(saved)).put("contracts",JSONArray(embedding.refresh().map{it.protocolJson()})).put("health",health())
            program.evaluate(SandboxBundle.build(context,listOf("protocol-contracts.js","protocol-host.js","public-protocol.js"),"","protocolInitialize(factories,${JSONObject.quote(consumer)},$bootstrap)",assetRoot=assets))
            embedding.registry.permissions { module -> program.evaluate("JSON.stringify(publicProtocol.permitted(${JSONObject.quote(module)}))").let{if(it=="null")null else JSONArray("[$it]").getString(0)} }
            driving.scheduleWithFixedDelay({try{advance()}catch(error:Throwable){for(future in futures.values)future.completeExceptionally(error)}},0,5,TimeUnit.MILLISECONDS)
        }catch(e:Throwable){program.close();SandboxEngine.release(engine);workers.shutdownNow();serial.shutdownNow();driving.shutdownNow();throw e}
    }
    @Synchronized fun start(name:String,args:JSONArray=JSONArray()):String {
        check(!ended);val key=(++sequence).toString()
        program.evaluate("protocolCall(${JSONObject.quote(key)},${JSONObject.quote(name)},$args)")
        futures[key]=CompletableFuture();return key
    }
    @Synchronized private fun advance(){
        check(!ended)
        val outgoing=JSONArray(program.evaluate("dealCapabilities.take()"))
        for(i in 0 until outgoing.length()){
            val r=outgoing.getJSONObject(i);val name=r.getString("function");val args=r.getJSONArray("args")
            if(r.getString("module")=="embedding/control") {
                if(name=="release"){val e=ref(args.getJSONObject(0));releases[e.id]=e}
                else try{control(name,args)}catch(error:Throwable){embedding.controlFailure(name,error)}
                continue
            }
            require(r.getString("module")=="embedding")
            // Native resource/session operations are serialized. Generation must not occupy that lane.
            val executor=if(name=="generate")workers else serial
            if(name=="next") {
                try{val wait=embedding.nextAsync(Session(args.getJSONObject(0).getString("id")),args.getString(1));val requestId=r.getInt("id");nextWaits[requestId]=wait
                    wait.whenComplete{update,error->
                    nextWaits.remove(requestId)
                    if(wait.isCancelled)return@whenComplete
                    val reply=JSONObject().put("id",r.getInt("id"));if(error==null){reply.put("ok",true).put("value",update.json());if(update.snapshot.fault.isNotEmpty())reply.put("invalidSessions",JSONArray(embedding.liveSessions().map{it.id}))}else reply.put("ok",false).put("error",error(error)).put("invalidSessions",JSONArray(embedding.liveSessions().map{it.id}));completed.add(reply)
                }}catch(error:Throwable){completed.add(JSONObject().put("id",r.getInt("id")).put("ok",false).put("error",error(error)).put("invalidSessions",JSONArray(embedding.liveSessions().map{it.id})))}
                continue
            }
            executor.execute {
                val reply=JSONObject().put("id",r.getInt("id"))
                try{
                    val preparation=if(name=="generate"){
                        val key=args.getString(0);val state=args.getJSONObject(1);val facts=state.getJSONObject("context")
                        val request=embedding.protocolRequest(state.getString("title"),state.getString("intent"),WireType.parse(facts.getJSONObject("schema")),facts.getString("json"),state.getJSONArray("contracts"),state.getString("trace"))
                        generatedTraces[key]=state.getString("trace");requests[key]=request;if(key in cancelled)embedding.cancel(request);request
                    }else null
                    reply.put("ok",true).put("value",invoke(name,args,preparation))
                }catch(e:Throwable){reply.put("ok",false).put("error",error(e));if(name in setOf("dispatch","run"))reply.put("invalidSessions",JSONArray(embedding.liveSessions().map{it.id}))}
                completed.add(reply)
            }
        }
        drainReleases()
        val replies=JSONArray();while(true){val r=completed.poll()?:break;replies.put(r)}
        if(replies.length()>0)program.evaluate("dealCapabilities.deliver($replies)")
        for(i in 0 until replies.length()){val r=replies.getJSONObject(i);r.optJSONArray("invalidSessions")?.let{program.evaluate("publicProtocol.liveSessions($it); ''")}}
        program.evaluate("publicProtocol.observed(${health()}); ''")
        for((key,future)in futures){if(future.isDone)continue;val result=JSONObject(program.evaluate("JSON.stringify(publicResults.get(${JSONObject.quote(key)}))"));if(result.optBoolean("done")){if(result.has("error")){val e=result.getJSONObject("error");future.completeExceptionally(FailureEvidence.decode(e))}else future.complete(result.opt("value")?:JSONObject.NULL);program.evaluate("publicResults.delete(${JSONObject.quote(key)}); ''")}}
    }
    @Synchronized internal fun detach(key:String){
        if(ended){futures.remove(key)?.completeExceptionally(ProtocolFailure("CLOSED","Controller closed"));return}
        try{
            val ids=JSONArray(program.evaluate("dealCapabilities.cancelOwner(${JSONObject.quote(key)})"))
            for(i in 0 until ids.length())nextWaits.remove(ids.getInt(i))?.cancel(false)
            program.evaluate("publicResults.delete(${JSONObject.quote(key)}); ''")
        }finally{futures.remove(key)?.completeExceptionally(ProtocolFailure("DETACHED","Observer detached"))}
    }
    fun await(key:String,timeoutSeconds:Long=-1):Any {
        require(timeoutSeconds == -1L || timeoutSeconds > 0)
        val deadline=if(timeoutSeconds == -1L || timeoutSeconds==Long.MAX_VALUE)Long.MAX_VALUE else System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val future=futures[key]?:error("Unknown invocation")
        return try{
            while(!future.isDone){check(System.nanoTime()<deadline){"Protocol consumer timeout"};Thread.sleep(5)}
            future.get()
        }catch(e:ExecutionException){throw e.cause!!}finally{if(!future.isDone)detach(key);futures.remove(key)}
    }
    fun call(name:String,args:JSONArray=JSONArray(),timeoutSeconds:Long=-1):Any=await(start(name,args),timeoutSeconds)
    @Synchronized fun synchronous(name:String,args:JSONArray=JSONArray()):Any {
        check(!ended)
        val declared=program.evaluate("String(publicConsumer[${JSONObject.quote(name)}].\$sig.startsWith('async'))")
        require(declared=="false"){"Async export cannot be invoked synchronously"}
        val result=JSONObject(program.evaluate("(() => {try {return JSON.stringify({ok:true,value:protocolInvoke(${JSONObject.quote(name)},$args)});}catch(error){return JSON.stringify({ok:false,code:error.code||'EMBEDDING_FAILED',message:error.message||'Embedding failed'});}})()"))
        if(!result.getBoolean("ok"))throw ProtocolFailure(result.getString("code"),result.getString("message"))
        if(name=="capture")embedding.capture(args.getBoolean(0))
        return result.opt("value") ?: JSONObject.NULL
    }
    @Synchronized fun capturing():Boolean=embedding.capturesContent()
    fun update(session:Session,cursor:String=""):SessionUpdate=(call("next",JSONArray().put(session.id).put(cursor),Long.MAX_VALUE) as JSONObject).let{SessionUpdate(UiSnapshot.parse(it.getJSONObject("snapshot")),it.getString("cursor"))}
    private fun control(name:String,args:JSONArray){when(name){
        "cancel"->{val key=args.getString(0);cancelled.add(key);requests[key]?.let{embedding.cancel(it)}}
        "release"->embedding.release(ref(args.getJSONObject(0)))
        "published"->embedding.published(Session(args.getJSONObject(0).getString("id")),args.getInt(1))
        else->error("Unknown controller instruction")
    }}
    private fun invoke(name:String,args:JSONArray,request:GenerationRequest?):Any=when(name){
        "generate"->prepared(embedding.generate(request!!)).also{it.put("trace",generatedTraces[args.getString(0)])}
        "load"->prepared(embedding.load(args.getString(0),args.getJSONObject(1).let{Source(it.getString("deal"),it.getString("dealui"))}))
        "run"->embedding.run(ref(args.getJSONObject(0))).let{JSONObject().put("id",it.id).put("trace",embedding.sessionTrace(it))}
        "dispatch"->{embedding.dispatch(Session(args.getJSONObject(0).getString("id")),args.getInt(1),if(args.isNull(2))null else args.getString(2));JSONObject.NULL}
        "close"->{embedding.close(Session(args.getJSONObject(0).getString("id")));JSONObject.NULL}
        "forget"->{embedding.forget(ref(args.getJSONObject(0)));JSONObject.NULL}
        "refresh"->JSONArray(embedding.refresh().map{it.protocolJson()})
        "events"->embedding.events(args.getString(0),args.getString(1),args.getInt(2)).let{JSONObject().put("events",JSONArray(it.events)).put("cursor",it.cursor).put("incomplete",it.incomplete)}
        "artifact"->embedding.artifact(args.getString(0)).let{JSONObject().put("status",it.status).put("text",it.text)}
        "clearObservation"->{embedding.clearObservation();JSONObject.NULL}
        "shutdown"->{shutdownRequested=true;embedding.shutdown();JSONObject.NULL}
        else->error("Unknown native mechanism")
    }
    private fun prepared(e:Experience)=JSONObject().put("experience",experience(e)).put("source",source(embedding.source(e)))
    private fun ref(v:JSONObject)=Experience(v.getString("id"),"","",0)
    private fun experience(e:Experience)=JSONObject().put("id",e.id).put("title",e.title).put("origin",e.origin).put("attempts",e.attempts)
    private fun source(s:Source)=JSONObject().put("deal",s.deal).put("dealui",s.dealui)
    private fun health()=embedding.health().let{JSONObject().put("incomplete",it.incomplete).put("active",it.active).put("code",it.code).put("lost",it.lost)}
    private fun error(e:Throwable):JSONObject=FailureEvidence.encode(e,::failureCode)
    private fun failureCode(e:Throwable):String=when(e){is ProtocolFailure->e.code;is CancellationException->"CANCELLED";is GenerationRejected->"GENERATION_${e.reason.name}";is java.net.SocketTimeoutException->"MODEL_TIMEOUT";is java.net.ConnectException->"GATEWAY_UNREACHABLE";is CandidateRejected->"CANDIDATE_REJECTED";is IllegalArgumentException->"INVALID_ARGUMENT";else->"EMBEDDING_FAILED"}
    override fun close(){
        if(ended)return
        var failure:Throwable?=null
        fun attempt(block:()->Unit){try{block()}catch(error:Throwable){if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)}}
        // Reclamation failure must never veto execution/resource teardown.
        attempt{flushReclamation()}
        // Let the trusted consumer terminate waits before releasing its isolate.
        if(!shutdownRequested)attempt{call("shutdown",timeoutSeconds=10)}
        synchronized(this){
            if(ended)return
            ended=true
            attempt{driving.shutdownNow()}
            attempt{workers.shutdownNow()}
            attempt{serial.shutdownNow()}
        }
        attempt{embedding.shutdown()}
        val closed=ProtocolFailure("CLOSED","Controller closed")
        nextWaits.values.toList().forEach{it.completeExceptionally(closed)};nextWaits.clear()
        futures.values.toList().forEach{it.completeExceptionally(closed)};futures.clear()
        for(executor in listOf(driving,workers,serial))attempt{
            check(executor.awaitTermination(10,TimeUnit.SECONDS)){"Controller executor did not terminate"}
        }
        attempt{program.close()}
        attempt{SandboxEngine.release(engine)}
        failure?.let{throw it}
    }
}
