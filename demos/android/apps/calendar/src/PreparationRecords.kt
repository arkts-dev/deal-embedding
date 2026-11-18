package dev.deal.apps.calendar

import android.content.Context
import dev.deal.embedding.*
import org.json.*

/** App-private provenance and fact identity. Execution/source/draft storage remain in embedding. */
object PreparationRecords {
    fun prefs(context: Context) = context.getSharedPreferences("preparation-records", 0)
    @Synchronized fun record(context: Context, stage: String, value: JSONObject) {
        val p = prefs(context)
        val events = JSONArray(p.getString("events", "[]"))
        if (events.length() >= 40) events.remove(0)
        events.put(JSONObject(value.toString()).put("stage", stage).put("at", System.currentTimeMillis()))
        check(p.edit().putString("events", events.toString()).commit())
    }
    fun opportunityApi(context: Context, capabilities: List<dev.deal.embedding.capabilities.NativeCapabilities> = emptyList()): ApplicationProtocol {
        return Embedding.open(context, EmbeddingConfig("preparation-opportunity", GenerationBuild.gateway(), dev.deal.shell.AssistanceBuild.discoveryGateway(), retainState = true), capabilities,
            trustProvider = { uid -> uid != context.applicationInfo.uid && dev.deal.shell.Connections.trusted(context,uid) })
    }
}
