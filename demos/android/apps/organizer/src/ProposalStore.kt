package dev.deal.apps.organizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Organizer-owned durable prepared selections, including per-experience review links. */
internal class ProposalStore(context: Context) {
    private val preferences = context.getSharedPreferences("prepared", 0)
    fun records(): JSONArray = JSONArray(preferences.getString("operations", "[]"))
    fun append(record: JSONObject, reference: String, experience: String?) {
        val all = records().put(record)
        val edit = preferences.edit().putString("operations", all.toString()).putString("last", reference)
        experience?.let { id ->
            edit.putString("workspace.$id", record.toString()).putString("workspace.$id.reference", reference)
        }
        check(edit.commit()) { "Cannot retain prepared selection" }
    }
}
