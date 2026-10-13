package dev.deal.embedding

import android.content.Context
import org.json.*
import java.util.UUID
import java.util.concurrent.*

class ProtocolFailure(val code:String, message:String):IllegalStateException(message)

/** Native bootstrap for a trusted compiled DEAL consumer. Never installed in a UI isolate.
 * The controller owns synchronous state; workers implement asynchronous mechanisms.
 * The evaluation pump never waits for a mechanism to finish, so controls remain serviceable.
 */
class ProtocolController(context:Context, private val embedding:Embedding, assets:String="protocol-consumer", consumer:String="consumer.js"):AutoCloseable {
    private val engine=SandboxEngine.acquire(context)
    private val program=SandboxProgram(engine,1024*1024)
    private val workers=Executors.newCachedThreadPool()
    private val serial=Executors.newSingleThreadExecutor()
    private val completed=ConcurrentLinkedQueue<JSONObject>()
    private val requests=ConcurrentHashMap<String,GenerationRequest>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val futures=ConcurrentHashMap<String,CompletableFuture<Any>>()
    private var sequence=0
    private val generatedTraces=ConcurrentHashMap<String,String>()
    private var ended=false
    init {
        try {
            val saved=embedding.saved().map{e->JSONObject().put("experience",experience(e)).put("source",source(embedding.source(e)))}
            val bootstrap=JSONObject().put("seed",UUID.randomUUID().toString()).put("saved",JSONArray(saved)).put("contracts",JSONArray(embedding.refresh().map{it.protocolJson()})).put("health",health())
            program.evaluate(SandboxBundle.build(context,listOf("protocol-contracts.js","protocol-host.js","public-protocol.js"),"","protocolInitialize(factories,${JSONObject.quote(consumer)},$bootstrap)",assetRoot=assets))
        }catch(e:Throwable){program.close();SandboxEngine.release(engine);workers.shutdownNow();serial.shutdownNow();throw e}
    }
    @Synchronized fun start(name:String,args:JSONArray=JSONArray()):String {
        check(!ended);val key=(++sequence).toString()
        program.evaluate("protocolCall(${JSONObject.quote(key)},${JSONObject.quote(name)},$args)")
        futures[key]=CompletableFuture();pump();return key
    }
    @Synchronized fun pump(){
        check(!ended)
        val outgoing=JSONArray(program.evaluate("dealCapabilities.take()"))
        for(i in 0 until outgoing.length()){
            val r=outgoing.getJSONObject(i);val name=r.getString("function");val args=r.getJSONArray("args")
            if(r.getString("module")=="embedding/control") {control(name,args);continue}
            require(r.getString("module")=="embedding")
            // Native resource/session operations are serialized. Generation must not occupy that lane.
            val executor=if(name=="generate")workers else serial
            executor.execute {
                val reply=JSONObject().put("id",r.getInt("id"))
                try{
                    val preparation=if(name=="generate"){
                        val key=args.getString(0);val state=args.getJSONObject(1);val facts=state.getJSONObject("context")
                        val request=embedding.protocolRequest(state.getString("title"),state.getString("intent"),WireType.parse(facts.getJSONObject("schema")),facts.getString("json"),state.getJSONArray("contracts"),state.getString("trace"))
                        generatedTraces[key]=state.getString("trace");requests[key]=request;if(key in cancelled)embedding.cancel(request);request
                    }else null
                    reply.put("ok",true).put("value",invoke(name,args,preparation))
                }catch(e:Throwable){reply.put("ok",false).put("error",error(e));if(name in setOf("dispatch","poll"))reply.put("invalidSessions",JSONArray(embedding.liveSessions().map{it.id}))}
                completed.add(reply)
            }
        }
        val replies=JSONArray();while(true){val r=completed.poll()?:break;replies.put(r)}
        for(i in 0 until replies.length()){val r=replies.getJSONObject(i);r.optJSONArray("invalidSessions")?.let{program.evaluate("publicProtocol.liveSessions($it); ''")}}
        if(replies.length()>0)program.evaluate("dealCapabilities.deliver($replies)")
        program.evaluate("publicProtocol.observed(${health()}); ''")
        for((key,future)in futures){if(future.isDone)continue;val result=JSONObject(program.evaluate("JSON.stringify(publicResults.get(${JSONObject.quote(key)}))"));if(result.optBoolean("done")){if(result.has("error")){val e=result.getJSONObject("error");future.completeExceptionally(ProtocolFailure(e.getString("code"),e.getString("message")))}else future.complete(result.opt("value")?:JSONObject.NULL)}}
    }
    fun await(key:String,timeoutSeconds:Long=120):Any {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val future=futures[key]?:error("Unknown invocation")
        while(!future.isDone){check(System.nanoTime()<deadline){"Protocol consumer timeout"};pump();Thread.sleep(5)}
        return try{future.get()}catch(e:ExecutionException){throw e.cause!!}
    }
    fun call(name:String,args:JSONArray=JSONArray(),timeoutSeconds:Long=120):Any=await(start(name,args),timeoutSeconds)
    private fun control(name:String,args:JSONArray){when(name){
        "cancel"->{val key=args.getString(0);cancelled.add(key);requests[key]?.let{embedding.cancel(it)}}
        "grant"->embedding.grant(args.getString(0))
        "revoke"->embedding.revoke(args.getString(0))
        "capture"->embedding.capture(args.getBoolean(0))
        "release"->embedding.release(ref(args.getJSONObject(0)))
        "published"->embedding.published(Session(args.getJSONObject(0).getString("id")),args.getInt(1))
        else->error("Unknown controller instruction")
    }}
    private fun invoke(name:String,args:JSONArray,request:GenerationRequest?):Any=when(name){
        "generate"->prepared(embedding.generate(request!!)).also{it.put("trace",generatedTraces[args.getString(0)])}
        "load"->prepared(embedding.load(args.getString(0),args.getJSONObject(1).let{Source(it.getString("deal"),it.getString("dealui"))}))
        "run"->embedding.run(ref(args.getJSONObject(0))).let{JSONObject().put("id",it.id).put("trace",embedding.sessionTrace(it))}
        "dispatch"->embedding.dispatch(Session(args.getJSONObject(0).getString("id")),args.getInt(1),if(args.isNull(2))null else args.getString(2)).json()
        "poll"->embedding.poll(Session(args.getJSONObject(0).getString("id"))).json()
        "close"->{embedding.close(Session(args.getJSONObject(0).getString("id")));JSONObject.NULL}
        "forget"->{embedding.forget(ref(args.getJSONObject(0)));JSONObject.NULL}
        "refresh"->JSONArray(embedding.refresh().map{it.protocolJson()})
        "events"->embedding.events(args.getString(0),args.getString(1),args.getInt(2)).let{JSONObject().put("events",JSONArray(it.events)).put("cursor",it.cursor).put("incomplete",it.incomplete)}
        "artifact"->embedding.artifact(args.getString(0)).let{JSONObject().put("status",it.status).put("text",it.text)}
        "clearObservation"->{embedding.clearObservation();JSONObject.NULL}
        "shutdown"->{embedding.shutdown();JSONObject.NULL}
        else->error("Unknown native mechanism")
    }
    private fun prepared(e:Experience)=JSONObject().put("experience",experience(e)).put("source",source(embedding.source(e)))
    private fun ref(v:JSONObject)=Experience(v.getString("id"),"","",0)
    private fun experience(e:Experience)=JSONObject().put("id",e.id).put("title",e.title).put("origin",e.origin).put("attempts",e.attempts)
    private fun source(s:Source)=JSONObject().put("deal",s.deal).put("dealui",s.dealui)
    private fun health()=embedding.health().let{JSONObject().put("incomplete",it.incomplete).put("active",it.active).put("code",it.code).put("lost",it.lost)}
    private fun error(e:Throwable):JSONObject {
        val code=when(e){is ProtocolFailure->e.code;is CancellationException->"CANCELLED";is GenerationRejected->"GENERATION_REJECTED";is CandidateRejected->"CANDIDATE_REJECTED";is IllegalArgumentException->"INVALID_ARGUMENT";else->"EMBEDDING_FAILED"}
        return JSONObject().put("code",code).put("message",e.message?:"Native mechanism failed")
    }
    @Synchronized override fun close(){if(ended)return;ended=true;requests.values.forEach{runCatching{embedding.cancel(it)}};workers.shutdown();serial.shutdown();workers.awaitTermination(10,TimeUnit.SECONDS);serial.awaitTermination(10,TimeUnit.SECONDS);embedding.shutdown();program.close();SandboxEngine.release(engine)}
}
