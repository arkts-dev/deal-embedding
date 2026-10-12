package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Native service linkage for every public DEAL export; records never mint resources. */
class PublicProtocolBinding(private val embedding:Embedding) {
    private fun experience(v:JSONObject)=Experience(v.getString("id"),"","",0)
    private fun session(v:JSONObject)=Session(v.getString("id"))
    private fun request(v:JSONObject)=GenerationRequest(v.getString("id"))
    private fun context(v:JSONObject)=GenerationContext(v.getString("id"))
    fun invoke(name:String,args:JSONArray):Any = when(name) {
        "snapshot" -> embedding.snapshot(WireType.parse(args.getJSONObject(0)),args.getString(1))
        "contextSchema" -> embedding.contextSchema(context(args.getJSONObject(0)))
        "contextJson" -> embedding.contextJson(context(args.getJSONObject(0)))
        "request" -> embedding.request(args.getString(0),args.getString(1),context(args.getJSONObject(2)))
        "generate" -> embedding.generate(request(args.getJSONObject(0)))
        "cancel" -> {embedding.cancel(request(args.getJSONObject(0)));JSONObject.NULL}
        "load" -> embedding.load(args.getString(0),args.getJSONObject(1).let{Source(it.getString("deal"),it.getString("dealui"))})
        "source" -> embedding.source(experience(args.getJSONObject(0)))
        "release" -> {embedding.release(experience(args.getJSONObject(0)));JSONObject.NULL}
        "run" -> embedding.run(experience(args.getJSONObject(0)))
        "dispatch" -> embedding.dispatch(session(args.getJSONObject(0)),args.getInt(1),if(args.isNull(2))null else args.getString(2))
        "poll" -> embedding.poll(session(args.getJSONObject(0)))
        "published" -> {embedding.published(session(args.getJSONObject(0)),args.getInt(1));JSONObject.NULL}
        "close" -> {embedding.close(session(args.getJSONObject(0)));JSONObject.NULL}
        "saved" -> embedding.saved()
        "forget" -> {embedding.forget(experience(args.getJSONObject(0)));JSONObject.NULL}
        "refresh" -> embedding.refresh().map{it.protocolJson()}
        "grant" -> {embedding.grant(args.getString(0));JSONObject.NULL}
        "revoke" -> {embedding.revoke(args.getString(0));JSONObject.NULL}
        "requestTrace" -> embedding.requestTrace(request(args.getJSONObject(0)))
        "sessionTrace" -> embedding.sessionTrace(session(args.getJSONObject(0)))
        "events" -> embedding.events(args.getString(0),args.getString(1),args.getInt(2))
        "artifact" -> embedding.artifact(args.getString(0))
        "health" -> embedding.health()
        "capture" -> {embedding.capture(args.getBoolean(0));JSONObject.NULL}
        "clearObservation" -> {embedding.clearObservation();JSONObject.NULL}
        "shutdown" -> {embedding.shutdown();JSONObject.NULL}
        else -> error("Unknown public protocol operation")
    }.let(::wire)
    private fun wire(v:Any):Any = when(v) {
        is WireType -> v.json()
        is GenerationContext -> JSONObject().put("id",v.id)
        is GenerationRequest -> JSONObject().put("id",v.id)
        is Session -> JSONObject().put("id",v.id)
        is Experience -> JSONObject().put("id",v.id).put("title",v.title).put("origin",v.origin).put("attempts",v.attempts)
        is Source -> JSONObject().put("deal",v.deal).put("dealui",v.dealui)
        is UiSnapshot -> v.json()
        is EventPage -> JSONObject().put("events",JSONArray(v.events.map{event->JSONObject().also{out->
            for(key in listOf("trace","span","parent","stage","outcome","code","experience","session","operation","target","time","elapsed","artifact","content","sha256"))out.put(key,event.optString(key))
            for(key in listOf("durationMs","version","sequence","bytes"))out.put(key,event.optInt(key,if(key=="version")-1 else 0))
        }})).put("cursor",v.cursor).put("incomplete",v.incomplete)
        is Artifact -> JSONObject().put("status",v.status).put("text",v.text)
        is ObservationHealth -> JSONObject().put("incomplete",v.incomplete).put("active",v.active).put("code",v.code).put("lost",v.lost)
        is List<*> -> JSONArray(v.map{wire(it!!)})
        else -> v
    }
}
