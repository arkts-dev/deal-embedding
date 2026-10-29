package dev.deal.embedding

import android.content.Context
import dev.deal.embedding.capabilities.*
import org.json.*
import java.io.File
import java.util.UUID

/** Android bootstrap for the protocol. No generated experience receives this authority surface. */
class Embedding internal constructor(private val context: Context, internal val registry: CapabilityRegistry, private val model: ModelClient, bootstrap: RuntimeConfig) {
    companion object {
        fun open(context: Context, config: EmbeddingConfig, capabilities: List<NativeCapabilities> = emptyList(), trustProvider: (Int) -> Boolean = { false }): ApplicationProtocol {
            val application = context.applicationContext
            val registry = CapabilityRegistry(application, CapabilityDiscovery(application,trustProvider),capabilities)
            val model = config.inference?.let { provider -> object : ModelClient {
                override fun complete(input: String, previous: String, diagnostics: String, cancellation: GenerationCancellation, log: EmbeddingLog) =
                    provider.complete(InferenceRequest(input,previous,diagnostics),cancellation)
            } } ?: GatewayModelClient(config.generation)
            var mechanisms: Embedding? = null
            try {
                val owner = Embedding(application,registry,model,RuntimeConfig("experience",emptyList(),config.storageName,
                    policyAssets=config.policy?.assetNamespace.orEmpty(),retainState=config.retainState))
                mechanisms = owner
                return ApplicationProtocol(application,owner,config.discovery)
            } catch (error: Throwable) {
                runCatching { mechanisms?.shutdown() ?: registry.close() }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }
    private val config=bootstrap.copy(contracts=bootstrap.contracts.map{CapabilityContract.parse(it.json().toString())})
    private data class RequestState(val title:String,val intent:String,val context:GenerationContext,val contracts:List<CapabilityContract>,val cancellation:GenerationCancellation,val log:EmbeddingLog,var used:Boolean=false)
    private val contexts=mutableMapOf<String,Pair<WireType,String>>()
    private val requests=mutableMapOf<String,RequestState>()
    private val experiences=mutableMapOf<String,Experience>()
    private val candidates=mutableMapOf<String,CheckedCandidate>()
    private val sessions=mutableMapOf<String,String>()
    private val runtime=ExperienceRuntime(context,registry,config)
    private var stopped=false
    private data class Delivery(var snapshot:UiSnapshot, val cursors:UpdateCursor=UpdateCursor(), var revision:Long=0, var failure:Throwable?=null, val waiters:MutableList<Pair<Long,java.util.concurrent.CompletableFuture<SessionUpdate>>> = mutableListOf()) {
        val cursor get()=cursors.issue(revision)
    }
    private val deliveries=mutableMapOf<String,Delivery>()
    private val driver=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    init { StagingReclamation.retry(context);driver.scheduleWithFixedDelay({synchronized(this){if(!stopped)for((key,workspace) in sessions.toMap())try{
        val snapshot=UiSnapshot.parse(runtime.advance(workspace) ?: error("Closed session"));val d=deliveries.getValue(key)
        if(snapshot.json().toString()!=d.snapshot.json().toString()) {d.snapshot=snapshot;d.revision++;deliver(d)}
    }catch(error:Throwable){deliveries[key]?.let{d->d.failure=error;d.revision++;d.snapshot=UiSnapshot(d.snapshot.tree,d.snapshot.version,error.message ?: "Session failed");deliver(d)};sessions.remove(key)}}},10,10,java.util.concurrent.TimeUnit.MILLISECONDS) }
    private fun deliver(d:Delivery){val update=SessionUpdate(UiSnapshot.parse(d.snapshot.json()),d.cursor);val ready=d.waiters.filter{it.first<d.revision};d.waiters.removeAll(ready.toSet());ready.forEach{it.second.complete(update)}}
    @Synchronized internal fun nextAsync(session:Session,cursor:String):java.util.concurrent.CompletableFuture<SessionUpdate> {
        val d=deliveries[session.id] ?: throw ProtocolFailure("INVALID_HANDLE","Unissued or closed session")
        val revision=if(cursor.isEmpty())-1L else d.cursors.revision(cursor,d.revision)
        if(d.waiters.size>=8)throw ProtocolFailure("LIMIT","Too many pending update waits")
        val result=java.util.concurrent.CompletableFuture<SessionUpdate>()
        if(revision<d.revision)result.complete(SessionUpdate(UiSnapshot.parse(d.snapshot.json()),d.cursor))else if(d.failure!=null)result.completeExceptionally(ProtocolFailure("SESSION_FAILED",d.snapshot.fault))else {
            d.waiters.add(revision to result)
            result.whenComplete{_,_->synchronized(this){d.waiters.removeAll{it.second===result}}}
        }
        return result
    }
    fun next(session:Session,cursor:String):SessionUpdate=try{nextAsync(session,cursor).get()}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private fun endDelivery(key:String){deliveries.remove(key)?.let{d->val waits=d.waiters.toList();d.waiters.clear();waits.forEach{it.second.completeExceptionally(ProtocolFailure("CLOSED","Session closed"))}}}
    private fun id()=UUID.randomUUID().toString()
    @Synchronized fun snapshot(schema:WireType,valueJson:String):GenerationContext {
        check(!stopped)
        schema.validateSchema();val copy=WireType.parse(schema.json());val value=JSONTokener(valueJson).let{parser->val v=parser.nextValue();require(parser.nextClean()=='\u0000'){"Trailing JSON"};v};copy.validate(value)
        val normalized=JSONArray().put(value).toString().let{it.substring(1,it.length-1)}
        return GenerationContext(id()).also{contexts[it.id]=copy to normalized}
    }
    @Synchronized fun contextSchema(context:GenerationContext)=WireType.parse((contexts[context.id] ?: error("Invalid context")).first.json())
    @Synchronized fun contextJson(context:GenerationContext)=(contexts[context.id] ?: error("Invalid context")).second
    @Synchronized fun request(title:String,intent:String,context:GenerationContext):GenerationRequest {
        check(!stopped);require(title.isNotBlank()&&intent.isNotBlank());contextJson(context)
        return GenerationRequest(id()).also{requests[it.id]=RequestState(title,intent,context,config.contracts.map{CapabilityContract.parse(it.json().toString())},GenerationCancellation(),EmbeddingLog(this.context))}
    }
    /** Controller-to-mechanism admission preserves the controller's frozen catalogue and trace. */
    @Synchronized internal fun protocolRequest(title:String,intent:String,schema:WireType,json:String,contracts:JSONArray,trace:String):GenerationRequest {
        val facts=snapshot(schema,json);val request=request(title,intent,facts)
        val state=requests.getValue(request.id)
        requests[request.id]=state.copy(contracts=(0 until contracts.length()).map{CapabilityContract.parse(contracts.getJSONObject(it).toString())},log=EmbeddingLog.traced(context,trace))
        return request
    }
    fun generate(request:GenerationRequest):Experience {
        val state=synchronized(this){val s=requests[request.id] ?: error("Invalid request");check(!s.used&&!stopped);s.used=true;s}
        state.cancellation.check()
        val frozen=config.copy(contracts=state.contracts)
        val engine=synchronized(this){check(!stopped);runtime.sandbox()}
        val candidate=ExperienceGenerator(context,frozen,engine,registry).generate(state.intent,contextJson(state.context),model,state.cancellation,{},state.log,state.context.id,contextSchema(state.context).json())
        try { return synchronized(this){state.cancellation.check();check(!stopped);issue(state.title,candidate)} }catch(e:Throwable){runCatching{candidate.close()}.onFailure{e.addSuppressed(it)};throw e}
    }
    @Synchronized fun cancel(request:GenerationRequest){(requests[request.id] ?: error("Invalid request")).cancellation.cancel()}
    @Synchronized private fun issue(title:String,candidate:CheckedCandidate):Experience {
        val experience=Experience(candidate.identity,title,when(candidate.origin){WorkspaceOrigin.CATALOGUE->"catalogue";WorkspaceOrigin.AI_SOURCE->"generated";else->"supplied"},candidate.attempts)
        experiences[experience.id]=experience;candidates[experience.id]=candidate;return experience
    }
    @Synchronized fun load(title:String,source:Source):Experience {check(!stopped);require(title.isNotBlank());return issue(title,runtime.check(ExperienceSource(source.deal,source.dealui)))}
    @Synchronized private fun trusted(experience:Experience)=experiences[experience.id] ?: error("Invalid experience")
    @Synchronized fun source(experience:Experience):Source {
        trusted(experience);val source=candidates[experience.id]?.source ?: runtime.workspace(experience.id)?.source ?: ExperienceStorage(context,config.storageName).source(experience.id) ?: error("Source unavailable")
        return Source(source.deal,source.ui)
    }
    @Synchronized fun release(experience:Experience){
        if(!experiences.containsKey(experience.id))return // Idempotent deferred reclamation only; public handles remain checked in DEAL.
        trusted(experience);check(sessions.values.none{it==experience.id})
        candidates[experience.id]?.close();candidates.remove(experience.id)
        if(runtime.savedWorkspaces().none{it.id==experience.id})experiences.remove(experience.id)
    }
    @Synchronized fun run(experience:Experience):Session {
        check(!stopped);val e=trusted(experience)
        sessions.entries.firstOrNull{it.value==e.id}?.let{if(runtime.workspace(e.id)!=null)return Session(it.key);sessions.remove(it.key)}
        val prepared=candidates[e.id];val session=Session(id())
        val mounted=if(prepared!=null){runtime.open(e.title,prepared,e.id,session.id).also{candidates.remove(e.id)}}else runtime.reopen(e.id,session.id)
        val initial=UiSnapshot.parse(mounted.second)
        deliveries[session.id]=Delivery(initial)
        return session.also{sessions[it.id]=e.id}
    }
    @Synchronized fun experience(session:Session):Experience=experiences[sessions[session.id] ?: error("Invalid session")] ?: error("Invalid experience")
    @Synchronized fun dispatch(session:Session,actionSlot:Int,payload:String?){runtime.dispatch(experience(session).id,actionSlot,payload)}
    @Synchronized fun published(session:Session,version:Int){runtime.published(experience(session).id,version)}
    @Synchronized fun close(session:Session){
        if(deliveries[session.id]?.failure!=null){StagingReclamation.retry(context);endDelivery(session.id);return}
        val e=experience(session);runtime.closeWorkspace(e.id);StagingReclamation.retry(context)
        sessions.remove(session.id);endDelivery(session.id)
    }
    @Synchronized fun saved():List<Experience> = runtime.savedWorkspaces().map{saved -> experiences.getOrPut(saved.id){Experience(saved.id,saved.title,when(saved.origin){WorkspaceOrigin.CATALOGUE->"catalogue";WorkspaceOrigin.AI_SOURCE->"generated";else->"supplied"},saved.attempts)} }
    @Synchronized fun forget(experience:Experience){trusted(experience);sessions.entries.filter{it.value==experience.id}.map{it.key}.forEach{close(Session(it))};candidates[experience.id]?.close();candidates.remove(experience.id);runtime.forgetWorkspace(experience.id);experiences.remove(experience.id)}
    @Synchronized fun refresh():List<CapabilityContract>{check(!stopped);return registry.refresh().map{CapabilityContract.parse(it.json().toString())}.also{config.contracts=it.map{c->CapabilityContract.parse(c.json().toString())}}}
    @Synchronized fun liveSessions():List<Session> {sessions.entries.removeAll{runtime.workspace(it.value)==null};return sessions.keys.map{Session(it)}}
    @Synchronized fun requestTrace(request:GenerationRequest)=(requests[request.id] ?: error("Invalid request")).log.run
    @Synchronized fun sessionTrace(session:Session):String {val e=experience(session);return runtime.workspace(e.id)!!.session.logTrace()}
    private val observation by lazy{Observation(context)}
    internal fun controlFailure(operation:String,error:Throwable){EmbeddingLog(context).event("control","failed",operation=operation,code="CONTROL_FAILED")}
    fun events(trace:String,cursor:String,limit:Int)=observation.events(trace,cursor,limit)
    fun artifact(id:String)=observation.artifact(id)
    fun health()=observation.health()
    fun capture(enabled:Boolean)=EmbeddingLog.capture(context,enabled)
    fun capturesContent():Boolean=runCatching{context.getSharedPreferences("embedding-logging",0).getBoolean("content",false)}.getOrDefault(false)
    fun clearObservation(){EmbeddingLog.clear(context);EmbeddingLog.flush(context);observation.clear()}
    @Synchronized fun shutdown(){
        stopped=true;driver.shutdownNow();deliveries.keys.toList().forEach(::endDelivery)
        requests.values.forEach{it.cancellation.cancel()}
        var failure:Throwable?=null
        fun attempt(block:()->Unit){try{block()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}}
        for((id,candidate) in candidates.toMap())attempt{candidate.close();candidates.remove(id)}
        attempt{runtime.close()};attempt{registry.close()};attempt{StagingReclamation.retry(context)}
        contexts.clear();requests.clear();sessions.clear()
        if(failure==null)experiences.clear()
        failure?.let{throw it}
    }
}
