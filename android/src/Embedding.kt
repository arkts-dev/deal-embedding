package dev.deal.embedding

import android.content.Context
import dev.deal.embedding.capabilities.*
import org.json.*
import java.io.File
import java.util.UUID

/** Android bootstrap for the protocol. No generated experience receives this authority surface. */
class Embedding(private val context: Context, val registry: CapabilityRegistry, private val model: ModelClient, bootstrap: EmbeddingConfig) {
    private val config=bootstrap.copy(contracts=bootstrap.contracts.map{CapabilityContract.parse(it.json().toString())})
    private data class RequestState(val title:String,val intent:String,val context:GenerationContext,val contracts:List<CapabilityContract>,val cancellation:GenerationCancellation,val log:EmbeddingLog,var used:Boolean=false)
    private val contexts=mutableMapOf<String,Pair<WireType,String>>()
    private val requests=mutableMapOf<String,RequestState>()
    private val experiences=mutableMapOf<String,Experience>()
    private val candidates=mutableMapOf<String,CheckedCandidate>()
    private val sessions=mutableMapOf<String,String>()
    private val runtime=ExperienceRuntime(context,registry,config)
    private var stopped=false
    private fun id()=UUID.randomUUID().toString()
    @Synchronized fun snapshot(schema:WireType,valueJson:String):GenerationContext {
        check(!stopped);require(valueJson.toByteArray().size<=8192)
        schema.validateSchema();val copy=WireType.parse(schema.json());val value=JSONTokener(valueJson).let{parser->val v=parser.nextValue();require(parser.nextClean()=='\u0000'){"Trailing JSON"};v};copy.validate(value)
        val normalized=JSONArray().put(value).toString().let{it.substring(1,it.length-1)}
        return GenerationContext(id()).also{contexts[it.id]=copy to normalized}
    }
    @Synchronized fun contextSchema(context:GenerationContext)=WireType.parse((contexts[context.id] ?: error("Invalid context")).first.json())
    @Synchronized fun contextJson(context:GenerationContext)=(contexts[context.id] ?: error("Invalid context")).second
    @Synchronized fun request(title:String,intent:String,context:GenerationContext):GenerationRequest {
        check(!stopped);require(title.isNotBlank()&&title.length<=512&&intent.isNotBlank()&&intent.length<=4096);contextJson(context)
        return GenerationRequest(id()).also{requests[it.id]=RequestState(title,intent,context,config.contracts.map{CapabilityContract.parse(it.json().toString())},GenerationCancellation(),EmbeddingLog(this.context))}
    }
    fun generate(request:GenerationRequest):Experience {
        val state=synchronized(this){val s=requests[request.id] ?: error("Invalid request");check(!s.used&&!stopped);s.used=true;s}
        state.cancellation.check()
        val frozen=config.copy(contracts=state.contracts)
        val engine=synchronized(this){check(!stopped);runtime.sandbox()}
        val candidate=ExperienceGenerator(context,frozen,engine).generate(state.intent,contextJson(state.context),model,state.cancellation,{},state.log,state.context.id,contextSchema(state.context).json())
        try { return synchronized(this){state.cancellation.check();check(!stopped);issue(state.title,candidate)} }catch(e:Throwable){candidate.close();throw e}
    }
    @Synchronized fun cancel(request:GenerationRequest){(requests[request.id] ?: error("Invalid request")).cancellation.cancel()}
    @Synchronized private fun issue(title:String,candidate:CheckedCandidate):Experience {
        val experience=Experience(candidate.identity,title,when(candidate.origin){WorkspaceOrigin.CATALOGUE->"catalogue";WorkspaceOrigin.AI_SOURCE->"generated";else->"supplied"},candidate.attempts)
        experiences[experience.id]=experience;candidates[experience.id]=candidate;return experience
    }
    @Synchronized fun load(title:String,source:Source):Experience {check(!stopped);require(title.isNotBlank()&&title.length<=512);return issue(title,runtime.check(ExperienceSource(source.deal,source.dealui)))}
    @Synchronized private fun trusted(experience:Experience)=experiences[experience.id] ?: error("Invalid experience")
    @Synchronized fun source(experience:Experience):Source {
        trusted(experience);val source=candidates[experience.id]?.source ?: runtime.workspace(experience.id)?.source ?: ExperienceStorage(context,config.storageName).source(experience.id) ?: error("Source unavailable")
        return Source(source.deal,source.ui)
    }
    @Synchronized fun release(experience:Experience){trusted(experience);check(sessions.values.none{it==experience.id});candidates.remove(experience.id)?.close();if(runtime.savedWorkspaces().none{it.id==experience.id})experiences.remove(experience.id)}
    @Synchronized fun run(experience:Experience):Session {
        check(!stopped);val e=trusted(experience)
        sessions.entries.firstOrNull{it.value==e.id}?.let{return Session(it.key)}
        val prepared=candidates[e.id];val session=Session(id())
        if(prepared!=null){runtime.open(e.title,prepared,e.id,session.id);candidates.remove(e.id)}else runtime.reopen(e.id,session.id)
        return session.also{sessions[it.id]=e.id}
    }
    @Synchronized fun experience(session:Session):Experience=experiences[sessions[session.id] ?: error("Invalid session")] ?: error("Invalid experience")
    @Synchronized fun dispatch(session:Session,actionSlot:Int,payload:String?):UiSnapshot=UiSnapshot.parse(runtime.dispatch(experience(session).id,actionSlot,payload) ?: error("Closed session"))
    @Synchronized fun poll(session:Session):UiSnapshot=UiSnapshot.parse(runtime.poll(experience(session).id) ?: error("Closed session"))
    @Synchronized fun published(session:Session,version:Int){runtime.published(experience(session).id,version)}
    @Synchronized fun close(session:Session){val e=experience(session);try{runtime.closeWorkspace(e.id)}finally{sessions.remove(session.id)}}
    @Synchronized fun saved():List<Experience> = runtime.savedWorkspaces().map{saved -> experiences.getOrPut(saved.id){Experience(saved.id,saved.title,when(saved.origin){WorkspaceOrigin.CATALOGUE->"catalogue";WorkspaceOrigin.AI_SOURCE->"generated";else->"supplied"},saved.attempts)} }
    @Synchronized fun forget(experience:Experience){trusted(experience);sessions.entries.filter{it.value==experience.id}.map{it.key}.forEach{close(Session(it))};candidates.remove(experience.id)?.close();runtime.forgetWorkspace(experience.id);experiences.remove(experience.id)}
    @Synchronized fun refresh():List<CapabilityContract>{check(!stopped);return registry.refresh().map{CapabilityContract.parse(it.json().toString())}.also{config.contracts=it.map{c->CapabilityContract.parse(c.json().toString())}}}
    fun grant(module:String)=registry.grant(module)
    fun revoke(module:String)=registry.revoke(module)
    @Synchronized fun liveSessions():List<Session> = sessions.keys.map{Session(it)}
    @Synchronized fun requestTrace(request:GenerationRequest)=(requests[request.id] ?: error("Invalid request")).log.run
    @Synchronized fun sessionTrace(session:Session):String {val e=experience(session);return runtime.workspace(e.id)!!.session.logTrace()}
    private val observation by lazy{Observation(context)}
    fun events(trace:String,cursor:String,limit:Int)=observation.events(trace,cursor,limit)
    fun artifact(id:String)=observation.artifact(id)
    fun health()=observation.health()
    fun capture(enabled:Boolean)=EmbeddingLog.capture(context,enabled)
    fun capturesContent():Boolean=runCatching{context.getSharedPreferences("embedding-logging",0).getBoolean("content",false)}.getOrDefault(false)
    fun clearObservation(){EmbeddingLog.clear(context);EmbeddingLog.flush(context);observation.clear()}
    @Synchronized fun shutdown(){if(stopped)return;requests.values.forEach{it.cancellation.cancel()};candidates.values.forEach{it.close()};runtime.close();registry.close();contexts.clear();requests.clear();experiences.clear();sessions.clear();candidates.clear();stopped=true}
}
