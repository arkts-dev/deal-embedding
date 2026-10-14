package dev.deal.embedding

import org.json.JSONObject
import org.json.JSONArray

/** Shared observed-event presentation; no app-domain interpretation or fabricated outcomes. */
fun activitySummary(event: JSONObject): String = event.optString("summary").ifBlank {
    event.optString("stage") + " · " + event.optString("outcome") + " " + event.optString("target")
}
fun readableLogDetail(detail: String): String = runCatching {
    val args = JSONArray(detail)
    (0 until args.length()).joinToString("\n\n") { index ->
        val label = when (index) { 0 -> "Request / response"; 1 -> "Previous response"; 2 -> "Check feedback"; else -> "Argument ${index + 1}" }
        "$label\n${args.optString(index)}"
    }
}.getOrDefault(detail)
fun dialogueRole(event: JSONObject): String = if (event.optString("stage") == "check") when (event.optString("outcome")) {
    "started" -> "Checking reply"; "completed" -> "Check passed"; "failed", "rejected" -> "Check feedback"; else -> "Check event"
} else when (event.optString("outcome")) {
    "started" -> "Request to AI"; "response" -> "AI reply"; "cancelled" -> "Cancelled"; else -> "Failed"
}

/** Observed exchanges only; request/reply span correlation never fabricates missing events. */
data class DialogueExchange(val key:String,val stage:String,val events:List<JSONObject>)
fun dialogueExchanges(entries:List<JSONObject>,limit:Int=100):List<DialogueExchange>{
    require(limit in 1..100)
    val chronological=entries.sortedWith(compareBy<JSONObject>{it.optString("time").toLongOrNull()?:0L}.thenBy{it.optLong("sequence")})
    val groups=linkedMapOf<String,MutableList<JSONObject>>()
    val stages=mutableMapOf<String,String>()
    val latest=mutableMapOf<String,String>()
    for(event in chronological){
        val trace=event.optString("trace");val stage=event.optString("stage");val outcome=event.optString("outcome")
        if(stage in listOf("choice","source") && outcome in listOf("started","response","failed","cancelled")){
            val span=if(outcome=="response")event.optString("parent").ifEmpty{event.optString("span")}else event.optString("span")
            if(trace.isEmpty()||span.isEmpty())continue
            val key="$trace:$span"
            groups.getOrPut(key){mutableListOf()}.add(JSONObject(event.toString()));stages[key]=stage;latest[trace]=key
        }else if(stage=="check" && outcome in listOf("started","completed","failed","rejected"))latest[trace]?.let{groups.getValue(it).add(JSONObject(event.toString()))}
    }
    return groups.entries.reversed().take(limit).map{DialogueExchange(it.key,stages.getValue(it.key),it.value.toList())}
}

/** Frozen observation pages, bounded independently of a consumer's display limit. */
fun ApplicationProtocol.retainedEvents(trace:String="",maximum:Int=4096):EventPage {
    require(maximum in 1..4096)
    val events=mutableListOf<JSONObject>();var cursor="";var incomplete=false
    do{val page=events(trace,cursor,minOf(200,maximum-events.size));events.addAll(page.events);incomplete=incomplete||page.incomplete;cursor=page.cursor}while(cursor.isNotEmpty()&&events.size<maximum)
    return EventPage(events,"",incomplete||cursor.isNotEmpty())
}
