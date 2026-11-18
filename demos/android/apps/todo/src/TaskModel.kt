package dev.deal.apps.todo

import android.content.Context
import org.json.*
import java.time.*
import java.util.UUID

val TASK_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** A show task carries the work, its assignee, its deadline and the checks the organizer asked for. */
data class ChecklistItem(val text: String, val done: Boolean = false)
data class Task(
    val id: String,
    val title: String,
    val assignee: String,
    val due: Long,
    val location: String = "",
    val instructions: String = "",
    val show: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val complete: Boolean = false,
    val minutes: Int = 15,
    val links: List<String> = emptyList(),
) {
    val required: List<ChecklistItem> get() = checklist
    val canComplete: Boolean get() = required.all { it.done }
    val progress: Float get() = if (required.isEmpty()) 0f else required.count { it.done }.toFloat() / required.size
}

class TaskStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("provider", 0)

    fun tasks(): List<Task> {
        val text = prefs.getString("tasks", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                val checks = o.optJSONArray("checklist") ?: JSONArray()
                val links = o.optJSONArray("links") ?: JSONArray()
                Task(o.getString("id"), o.getString("title"), o.optString("assignee", "Unassigned"), o.optLong("due"),
                    o.optString("location"), o.optString("instructions"), o.optString("show"),
                    (0 until checks.length()).map { val c = checks.getJSONObject(it); ChecklistItem(c.getString("text"), c.optBoolean("done")) },
                    o.optBoolean("complete"), o.optInt("minutes", 15), (0 until links.length()).map { links.getString(it) })
            }
        }.getOrNull() ?: emptyList()
    }

    fun save(value: List<Task>) {
        val array = JSONArray()
        value.forEach { task ->
            array.put(JSONObject().put("id", task.id).put("title", task.title).put("assignee", task.assignee).put("due", task.due)
                .put("location", task.location).put("instructions", task.instructions).put("show", task.show)
                .put("checklist", JSONArray(task.checklist.map { JSONObject().put("text", it.text).put("done", it.done) }))
                .put("complete", task.complete).put("minutes", task.minutes).put("links", JSONArray(task.links)))
        }
        prefs.edit().putString("tasks", array.toString()).commit()
    }
    fun newId() = UUID.randomUUID().toString()

    companion object {
        fun at(date: LocalDate, hour: Int, minute: Int = 0): Long = date.atTime(hour, minute).atZone(TASK_ZONE).toInstant().toEpochMilli()
    }
}
