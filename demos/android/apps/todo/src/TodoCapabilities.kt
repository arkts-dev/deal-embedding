package dev.deal.apps.todo

import dev.deal.embedding.*
import dev.deal.embedding.WireField
import android.content.Context
import dev.deal.embedding.capabilities.*
import org.json.*

/** One contract drives the checker declarations, the sandbox bindings and the native handlers. */
fun todoCapabilities(context: Context): NativeCapabilities {
    val store = TaskStore(context)
    val contract = CapabilityContract("host/todo", "Show tasks: assigned work, deadlines, checklists and proposal outcomes", listOf(
        CapabilityFunction("unfinished", "Read opted-in unfinished task titles for foreground preparation discovery; no task writes", emptyList(),
            WireType("array", element = WireType("record", fields = listOf(WireField("id", WireType("string", maximum = 64)), WireField("title", WireType("string", maximum = 120)), WireField("complete", WireType("boolean")))), maximum = 8)),
        CapabilityFunction("tasks", "Read tasks shared for a show", listOf(Parameter("show", WireType("string", maximum = 120))),
            WireType("array", element = WireType("record", fields = listOf(WireField("id", WireType("string")), WireField("title", WireType("string")), WireField("assignee", WireType("string")), WireField("due", WireType("string")), WireField("complete", WireType("boolean")), WireField("checks", WireType("number")), WireField("done", WireType("number")))),
                maximum = 64)),
        CapabilityFunction("task", "Read one shared task", listOf(Parameter("id", WireType("string"))),
            WireType("record", fields = listOf(WireField("id", WireType("string")), WireField("title", WireType("string")), WireField("assignee", WireType("string")), WireField("due", WireType("string")), WireField("complete", WireType("boolean")), WireField("status", WireType("string"))))),
        CapabilityFunction("proposeTask", "Stage a task proposal for native review", listOf(
            Parameter("title", WireType("string", maximum = 120)), Parameter("assignee", WireType("string", maximum = 60)),
            Parameter("due", WireType("string")), Parameter("location", WireType("string", maximum = 120)),
            Parameter("instructions", WireType("string", maximum = 512)),
            Parameter("checks", WireType("array", element = WireType("string", maximum = 120), maximum = 16))),
            WireType("record", fields = listOf(WireField("proposalId", WireType("string")), WireField("state", WireType("string"))))),
        CapabilityFunction("outcome", "Read the persistent outcome of a task proposal", listOf(Parameter("proposalId", WireType("string"))),
            WireType("record", fields = listOf(WireField("state", WireType("string")), WireField("taskId", WireType("string")), WireField("detail", WireType("string"))))),
    ))
    return NativeCapabilities(contract, mapOf(
        "unfinished" to { _ -> JSONArray(store.tasks().filter { !it.complete }.take(8).map { task -> JSONObject().put("id", task.id).put("title", task.title.take(120)).put("complete", false) }) },
        "tasks" to { args ->
            val show = args.getString(0)
            JSONArray(store.tasks().filter { it.show == show }.map { task -> JSONObject().put("id", task.id).put("title", task.title)
                .put("assignee", task.assignee).put("due", iso(task.due)).put("complete", task.complete)
                .put("checks", task.checklist.size).put("done", task.checklist.count { it.done }) })
        },
        "task" to { args ->
            val task = store.tasks().firstOrNull { it.id == args.getString(0) } ?: throw IllegalArgumentException("Unknown task")
            JSONObject().put("id", task.id).put("title", task.title).put("assignee", task.assignee).put("due", iso(task.due))
                .put("complete", task.complete).put("status", if (task.complete) "completed" else "open")
        },
        "proposeTask" to { args -> propozal(context, store, args) },
        "outcome" to { args -> dev.deal.shell.proposalOutcome(context, "todo", args.getString(0), "taskId") },
    ))
}

/** Proposals persist, so the organizer can recover an outcome after either app restarts. */
private fun propozal(context: Context, store: TaskStore, args: JSONArray): Any {
    val title = args.getString(0)
    val assignee = args.getString(1)
    val due = args.getString(2)
    val location = args.getString(3)
    val instructions = args.getString(4)
    val checks = args.getJSONArray(5)
    val proposalId = "todo-" + java.util.UUID.randomUUID().toString().take(8)
    val prefs = context.getSharedPreferences("proposals", 0)
    val record = JSONObject().put("proposalId", proposalId).put("state", "pending").put("title", title).put("assignee", assignee)
        .put("due", due).put("location", location).put("instructions", instructions)
        .put("checks", JSONArray((0 until checks.length()).map { checks.getString(it) }))
    prefs.edit().putString("todo:$proposalId", record.toString()).commit()
    return JSONObject().put("proposalId", proposalId).put("state", "pending")
}

internal fun iso(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).atZone(TASK_ZONE).toString()
