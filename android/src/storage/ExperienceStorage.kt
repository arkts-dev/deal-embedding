package dev.deal.embedding

import android.content.Context
import java.io.File

internal class ExperienceStorage(private val context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, 0)
    fun distribution(): File = File(context.filesDir.canonicalFile, "distribution").also { copyAsset("distribution", it) }
    fun saveWorkspace(id: String, title: String, source: ExperienceSource, origin: WorkspaceOrigin, attempts: Int) {
        check(prefs.edit().putString("workspace.$id.title", title).putString("workspace.$id.deal", encode(source.deal)).putString("workspace.$id.dealui", encode(source.ui))
            .putString("workspace.$id.sourceEncoding", "base64").putString("workspace.$id.origin", origin.name).putInt("workspace.$id.attempts", attempts).commit()) { "Cannot persist workspace" }
    }
    // XML preference serialization can append indentation after a trailing newline.
    // Preserve checked source bytes instead of normalizing source to make a digest fit.
    private fun encode(value: String) = android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    private fun decode(id: String, value: String): String = if (prefs.getString("workspace.$id.sourceEncoding", "") == "base64")
        String(android.util.Base64.decode(value, android.util.Base64.NO_WRAP), Charsets.UTF_8) else value
    private fun digest(source: ExperienceSource) = java.security.MessageDigest.getInstance("SHA-256").digest((source.deal + "\u0000" + source.ui).toByteArray()).joinToString("") { "%02x".format(it) }
    fun saveState(id: String, source: ExperienceSource, state: String) {
        require(state.toByteArray().size <= 256 * 1024)
        check(prefs.edit().putString("workspace.$id.state", state).putString("workspace.$id.stateSource", digest(source)).commit()) { "Cannot persist draft" }
    }
    fun state(id: String, source: ExperienceSource): String? = if (prefs.getString("workspace.$id.stateSource", "") == digest(source)) prefs.getString("workspace.$id.state", null) else null
    fun forgetWorkspace(id: String) {
        check(prefs.edit().remove("workspace.$id.title").remove("workspace.$id.deal").remove("workspace.$id.dealui").remove("workspace.$id.origin").remove("workspace.$id.attempts").remove("workspace.$id.state").remove("workspace.$id.stateSource").remove("workspace.$id.sourceEncoding").commit()) { "Cannot delete experience" }
    }
    fun savedWorkspaces(): List<SavedWorkspace> = prefs.all.keys.filter { it.startsWith("workspace.") && it.endsWith(".deal") }
        .map { key ->
            val id = key.removePrefix("workspace.").removeSuffix(".deal")
            SavedWorkspace(id, prefs.getString("workspace.$id.title", "Workspace")!!,
                runCatching { WorkspaceOrigin.valueOf(prefs.getString("workspace.$id.origin", "")!!) }.getOrDefault(WorkspaceOrigin.SAVED_SOURCE),
                prefs.getInt("workspace.$id.attempts", 0).coerceIn(0, 3))
        }.sortedBy { it.title }
    fun source(id: String): ExperienceSource? = prefs.getString("workspace.$id.deal", null)?.let { deal ->
        prefs.getString("workspace.$id.dealui", null)?.let { ui -> ExperienceSource(decode(id, deal), decode(id, ui)) }
    }

    fun candidateDirectory(): File = File(context.filesDir.canonicalFile, "candidate-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private fun copyAsset(path: String, target: File) {
        val names = context.assets.list(path) ?: emptyArray()
        if (names.isEmpty()) { target.parentFile!!.mkdirs(); context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
        else { target.mkdirs(); names.forEach { copyAsset("$path/$it", File(target, it)) } }
    }
}
