package dev.deal.embedding

import android.content.Context
import org.json.JSONObject

/**
 * The framework `host/storage` mechanism for one Experience: application-private string records
 * scoped to the Experience that owns the live store. It never reads or writes the controller's
 * saved-experience record, so an experience cannot overwrite its own source, saved state or
 * permissions through the application store.
 */
internal class ExperienceScopedStorage(context: Context, name: String, private val experience: String) {
    private val prefs = context.getSharedPreferences("$name.experience-storage", 0)
    private fun key(key: String): String = "experience.$experience.$key"
    /** An absent key is the declared null, never an empty record. */
    fun load(key: String): Any = prefs.getString(key(key), null) ?: JSONObject.NULL
    /** The declared surface echoes the stored value, so the caller never assumes an unchecked write. */
    fun save(key: String, value: String): String {
        check(prefs.edit().putString(key(key), value).commit()) { "Cannot persist experience storage" }
        return value
    }
    fun remove(key: String): Boolean {
        val removed = prefs.contains(key(key))
        check(prefs.edit().remove(key(key)).commit()) { "Cannot remove experience storage" }
        return removed
    }
    /** The declared asynchronous surface validated at the boundary; an unissued operation refuses. */
    fun call(function: String, args: org.json.JSONArray): Any = when (function) {
        "load" -> load(args.getString(0))
        "save" -> save(args.getString(0), args.getString(1))
        "remove" -> remove(args.getString(0))
        else -> throw ProtocolFailure("INVALID_ARGUMENT", "Unissued storage operation")
    }
    /** Forget clears the Experience's application records with the rest of its persisted state. */
    companion object {
        fun forget(context: Context, name: String, experience: String) {
            val prefs = context.getSharedPreferences("$name.experience-storage", 0)
            val edit = prefs.edit()
            for (key in prefs.all.keys) if (key.startsWith("experience.$experience.")) edit.remove(key)
            check(edit.commit()) { "Cannot delete experience storage" }
        }
    }
}
