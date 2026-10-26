package dev.deal.embedding
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Bounded snapshot paging: appends cannot disrupt a page continuation. */
internal class Observation(private val context:Context) {
    private data class Page(val trace:String,val events:List<JSONObject>,val index:Int)
    private val cursors=linkedMapOf<String,Page>()
    private val directory get()=File(context.filesDir,"embedding-log")
    @Synchronized fun events(trace:String,cursor:String,limit:Int):EventPage {
        require(limit in 1..200 && trace.length<=80 && cursor.length<=80)
        return try {
            val page=if(cursor.isEmpty())Page(trace,listOf("previous.jsonl","events.jsonl").flatMap{File(directory,it).takeIf{it.isFile}?.readLines().orEmpty()}.map{JSONObject(it)}.filter{trace.isEmpty()||it.optString("trace")==trace},0) else (cursors.remove(cursor) ?: error("Expired observation cursor")).also{require(it.trace==trace)}
            val selected=page.events.drop(page.index).take(limit)
            val next=if(page.index+selected.size<page.events.size)UUID.randomUUID().toString().also{while(cursors.size>=16)cursors.remove(cursors.keys.first());cursors[it]=page.copy(index=page.index+selected.size)}else ""
            EventPage(selected.map{event->JSONObject(event.toString()).put("experience",event.optString("workspace").ifEmpty{event.optString("candidate")}).put("session",event.optString("session")).put("sha256",event.optString("sha256"))},next,health().incomplete)
        }catch(e:java.io.IOException){EventPage(emptyList(),"",true)}catch(e:org.json.JSONException){EventPage(emptyList(),"",true)}
    }
    fun artifact(id:String):Artifact {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val file=File(directory,"$id.json");if(!file.isFile)return Artifact("unavailable","")
        return try{val truncated=File(directory,"$id.truncated").isFile;Artifact(if(truncated)"truncated" else "captured",file.readText())}catch(e:Exception){Artifact("unavailable","")}
    }
    fun health():ObservationHealth = try{val file=File(directory,"health.json");if(!file.isFile)ObservationHealth(false,false,"",0)else JSONObject(file.readText()).let{ObservationHealth(it.optBoolean("incomplete"),it.optBoolean("active"),it.optString("code"),it.optInt("lost"))}}catch(e:Exception){ObservationHealth(true,true,"LOGGER_READ",0)}
    @Synchronized fun clear(){cursors.clear()}
}
