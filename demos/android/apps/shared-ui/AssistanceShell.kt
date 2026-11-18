package dev.deal.shell

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.deal.embedding.ApplicationProtocol
import java.util.concurrent.FutureTask

/** App-shell namespace/retention policy, not an embedding storage implementation. */
object AssistanceShell {
    fun storage(context: Context) = when (context.packageName.substringAfterLast('.')) {
        "calendar" -> "preparation-opportunity"
        "organizer" -> "organizer-protocol"
        else -> "assistance"
    }
    fun settings(context: Context) = Intent(context, AssistanceSettingsActivity::class.java)
    fun inspect(context: Context, experience: String = "", trace: String = "", since: Long = 0) =
        Intent(context, AssistanceInspectActivity::class.java).putExtra("experience", experience)
            .putExtra("trace", trace).putExtra("since", since)

    fun records(context: Context): List<org.json.JSONObject> {
        if (!context.packageName.endsWith(".calendar")) return emptyList()
        val records = org.json.JSONArray(context.getSharedPreferences("preparation-records", 0).getString("events", "[]"))
        return (0 until records.length()).map { records.getJSONObject(it) }.reversed()
    }

    private val participants = mutableMapOf<Any, () -> Unit>()
    @Volatile var resetting = false
        private set
    @Synchronized fun register(owner: Any, stop: () -> Unit) {
        check(!resetting) { "Assistance reset in progress" }; participants[owner] = stop
    }
    @Synchronized fun unregister(owner: Any) { participants.remove(owner) }
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else {
            val task = FutureTask { block() }; Handler(Looper.getMainLooper()).post(task); task.get()
        }
    }
    // One current app-owned namespace. Retired test/production stores were explicitly purged.
    fun stores(context: Context): List<String> = listOf(storage(context))

    /** Explicit reset only. Stop owners before forgetting, never erase observations or domain data.
     * Failed cleanup leaves links intact and the failure visible; reset is not retried automatically. */
    fun reset(context: Context, stores: List<String> = stores(context),
              open: (String) -> ApplicationProtocol = { assistanceProtocol(context, it) }) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Reset must run off the UI thread" }
        val stops = synchronized(this) {
            check(!resetting); resetting = true; participants.values.toList()
        }
        try {
            var failure: Throwable? = null
            stops.forEach { stop -> try { stop() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            failure?.let { throw it }
            stores.distinct().forEach { name -> open(name).use { api -> api.saved().forEach(api::forget) } }
            val settings = context.getSharedPreferences("hive", 0)
            val edit = settings.edit()
            settings.all.keys.filter { it.startsWith("saved.") || it.startsWith("dismissed.") }.forEach(edit::remove)
            check(edit.commit())
            if (context.packageName.endsWith(".organizer")) {
                check(context.getSharedPreferences("prepared", 0).edit().clear().commit())
            }
            if (context.packageName.endsWith(".calendar")) {
                check(context.getSharedPreferences("preparation-discovery-dismissals", 0).edit().clear().commit())
                val records = context.getSharedPreferences("preparation-records", 0)
                val links = records.edit()
                records.all.keys.filter { it.startsWith("opportunity.") }.forEach(links::remove)
                check(links.commit()) // Keep the app's failed-attempt/provenance events.
            }
        } finally { synchronized(this) { resetting = false } }
    }
}
