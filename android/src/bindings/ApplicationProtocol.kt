package dev.deal.embedding

import android.content.Context
import org.json.*
import java.util.concurrent.Executors

/** Rendering/lifecycle adapter to the compiled DEAL consumer. It never drives sessions. */
class ApplicationProtocol(private val context:Context, mechanisms:Embedding):AutoCloseable {
    private val controller=ProtocolController(context,mechanisms,"protocol-application","application.js")
    private val observers=Executors.newCachedThreadPool()
    private fun args(vararg values:Any?)=JSONArray(values.toList())
    private fun sync(name:String,vararg values:Any?)=controller.synchronous(name,args(*values))
    private fun call(name:String,vararg values:Any?)=controller.call(name,args(*values))
    private fun experience(v:Any)= (v as JSONObject).let{Experience(it.getString("id"),it.getString("title"),it.getString("origin"),it.getInt("attempts"))}
    fun snapshot(schema:WireType,json:String)=GenerationContext((sync("snapshot",schema.json(),json) as JSONObject).getString("id"))
    fun request(title:String,intent:String,context:GenerationContext)=GenerationRequest((sync("request",title,intent,context.id) as JSONObject).getString("id"))
    fun generate(request:GenerationRequest)=experience(call("generate",request.id))
    fun cancel(request:GenerationRequest){sync("cancel",request.id)}
    fun load(title:String,source:Source)=experience(call("load",title,source.deal,source.dealui))
    fun source(experience:Experience)=(sync("source",experience.id) as JSONObject).let{Source(it.getString("deal"),it.getString("dealui"))}
    fun release(experience:Experience){sync("release",experience.id)}
    /** Own a prepared result until the app accepts it. Failure releases preparation, not saved source. */
    fun <T> withPreparation(experience:Experience,accept:(Experience)->T):T=try{accept(experience)}catch(error:Throwable){
        runCatching{release(experience);flushReclamation()}.onFailure{error.addSuppressed(it)};throw error
    }
    fun flushReclamation()=controller.flushReclamation()
    fun run(experience:Experience)=Session((call("run",experience.id) as JSONObject).getString("id"))
    fun next(session:Session,cursor:String)=controller.update(session,cursor)
    fun observe(session:Session,render:(UiSnapshot)->Unit,onFailure:(Throwable)->Unit):AutoCloseable {
        val subscription=Subscription(session,render,onFailure)
        subscriptions.add(subscription)
        try{observers.execute{subscription.receive()}}catch(error:Throwable){runCatching{subscription.close()}.onFailure{error.addSuppressed(it)};throw error}
        return subscription
    }
    private val subscriptions=java.util.concurrent.ConcurrentHashMap.newKeySet<Subscription>()
    private inner class Subscription(val session:Session,val render:(UiSnapshot)->Unit,val failed:(Throwable)->Unit):AutoCloseable {
        private var detached=false
        private var invocation:String?=null
        fun receive(){
            try{var cursor=""
                while(true){
                    val key=synchronized(this){if(detached)return;controller.start("next",args(session.id,cursor)).also{invocation=it}}
                    val value=controller.await(key,Long.MAX_VALUE) as JSONObject
                    synchronized(this){
                        invocation=null;if(detached)return
                        cursor=value.getString("cursor");render(UiSnapshot.parse(value.getJSONObject("snapshot")))
                    }
                }
            }catch(error:Throwable){synchronized(this){if(!detached)failed(error)}}finally{close()}
        }
        @Synchronized override fun close(){
            if(detached)return
            detached=true
            try{invocation?.let{controller.detach(it)}}finally{invocation=null;subscriptions.remove(this)}
        }
    }
    internal fun surfaceFailure(session:Session,error:Throwable) {
        runCatching { EmbeddingLog.traced(context,sessionTrace(session)).bind("",session.id).event("host","failed",target="ExperienceSurface",code=error.javaClass.simpleName,detail=android.util.Log.getStackTraceString(error)) }
    }
    fun dispatch(session:Session,slot:Int,payload:String?){call("dispatch",session.id,slot,payload ?: JSONObject.NULL)}
    fun published(session:Session,version:Int){sync("published",session.id,version)}
    fun close(session:Session){call("close",session.id)}
    fun saved()=(sync("saved") as JSONArray).let{a->(0 until a.length()).map{experience(a.getJSONObject(it))}}
    fun forget(experience:Experience){call("forget",experience.id)}
    fun refresh()=(call("refresh") as JSONArray).let{a->(0 until a.length()).map{v->a.getJSONObject(v).let{c->val fs=c.getJSONArray("functions");SharedCapabilityContract(c.getString("module"),c.getString("description"),c.getInt("version"),(0 until fs.length()).map{i->val f=fs.getJSONObject(i);val ps=f.getJSONArray("parameters");SharedCapabilityFunction(f.getString("name"),f.getString("description"),(0 until ps.length()).map{j->val p=ps.getJSONObject(j);Parameter(p.getString("name"),WireType.parse(p.getJSONObject("type")))},WireType.parse(f.getJSONObject("result")))})}}}
    fun grant(module:String){sync("grant",module)}
    fun revoke(module:String){sync("revoke",module)}
    fun requestTrace(request:GenerationRequest)=sync("requestTrace",request.id) as String
    fun sessionTrace(session:Session)=sync("sessionTrace",session.id) as String
    fun events(trace:String,cursor:String,limit:Int)=(call("events",trace,cursor,limit) as JSONObject).let{v->val a=v.getJSONArray("events");EventPage((0 until a.length()).map{a.getJSONObject(it)},v.getString("cursor"),v.getBoolean("incomplete"))}
    fun artifact(id:String)=(call("artifact",id) as JSONObject).let{Artifact(it.getString("status"),it.getString("text"))}
    fun health()=(sync("health") as JSONObject).let{ObservationHealth(it.getBoolean("incomplete"),it.getBoolean("active"),it.getString("code"),it.getInt("lost"))}
    fun capturesContent()=controller.capturing()
    fun capture(enabled:Boolean){sync("capture",enabled)}
    fun clearObservation(){call("clearObservation")}
    fun shutdown()=teardown(
        *subscriptions.toList().map{subscription->{subscription.close()}}.toTypedArray(),
        {observers.shutdownNow()},
        {controller.close()},
        {check(observers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)){"Observer executor did not terminate"}}
    )
    override fun close()=shutdown()
}
