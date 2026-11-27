package dev.deal.embedding
import android.content.Context
import org.json.JSONObject
import java.io.File

/** Bounded snapshot paging: appends cannot disrupt a page continuation. */
internal class Observation(private val context:Context) {
    private val store=ObservationStore(File(context.filesDir,"embedding-log"))
    @Synchronized fun events(trace:String,cursor:String,limit:Int):EventPage {
        require(limit in 1..200 && trace.length<=80 && cursor.length<=80)
        return try {
            // The store decides paging, selection and continuation lifetime; this adapter owns only the
            // stored event format and the host-facing field names. Reading the format is not optional: a
            // line this reader cannot parse is recording loss, so the store reports that page incomplete
            // instead of selecting nothing from it and handing the host a complete page that omits it.
            val page=store.events(trace,cursor,limit){line->JSONObject(line).optString("trace")}
            EventPage(page.lines.map{line->JSONObject(line).let{event->event.put("experience",event.optString("workspace").ifEmpty{event.optString("candidate")}).put("session",event.optString("session")).put("sha256",event.optString("sha256"))}},page.cursor,health().incomplete||page.incomplete)
        }catch(e:org.json.JSONException){EventPage(emptyList(),"",true)}
    }
    fun artifact(id:String):Artifact = store.artifact(id).let{Artifact(it.status,it.text)}
    fun health():ObservationHealth = store.health().let{ObservationHealth(it.incomplete,it.active,it.code,it.lost)}
    @Synchronized fun clear(){store.clear()}
}
