package dev.deal.embedding

import android.content.Context
import java.io.File

internal class ExperienceStorage(private val context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, 0)
    fun remembered(): ExperienceSource? = prefs.getString("deal", null)?.let { ExperienceSource(it, prefs.getString("dealui", "")!!) }
    fun commit(source: ExperienceSource) { check(prefs.edit().putString("deal", source.deal).putString("dealui", source.ui).commit()) { "Cannot persist experience" } }
    fun distribution(): File = File(context.filesDir.canonicalFile, "distribution").also { copyAsset("distribution", it) }
    fun saveWorkspace(id: String, title: String, source: ExperienceSource, origin: WorkspaceOrigin, attempts: Int) {
        check(prefs.edit().putString("workspace.$id.title", title).putString("workspace.$id.deal", source.deal).putString("workspace.$id.dealui", source.ui)
            .putString("workspace.$id.origin", origin.name).putInt("workspace.$id.attempts", attempts).commit()) { "Cannot persist workspace" }
    }
    fun forgetWorkspace(id: String) {
        prefs.edit().remove("workspace.$id.title").remove("workspace.$id.deal").remove("workspace.$id.dealui").remove("workspace.$id.origin").remove("workspace.$id.attempts").commit()
    }
    fun savedWorkspaces(): List<SavedWorkspace> = prefs.all.keys.filter { it.startsWith("workspace.") && it.endsWith(".deal") }
        .map { key ->
            val id = key.removePrefix("workspace.").removeSuffix(".deal")
            SavedWorkspace(id, prefs.getString("workspace.$id.title", "Workspace")!!,
                runCatching { WorkspaceOrigin.valueOf(prefs.getString("workspace.$id.origin", "")!!) }.getOrDefault(WorkspaceOrigin.SAVED_SOURCE),
                prefs.getInt("workspace.$id.attempts", 0).coerceIn(0, 3))
        }.sortedBy { it.title }
    fun source(id: String): ExperienceSource? = prefs.getString("workspace.$id.deal", null)?.let { deal ->
        prefs.getString("workspace.$id.dealui", null)?.let { ui -> ExperienceSource(deal, ui) }
    }

    fun candidateDirectory(): File = File(context.filesDir.canonicalFile, "candidate-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private fun copyAsset(path: String, target: File) {
        val names = context.assets.list(path) ?: emptyArray()
        if (names.isEmpty()) { target.parentFile!!.mkdirs(); context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
        else { target.mkdirs(); names.forEach { copyAsset("$path/$it", File(target, it)) } }
    }
}
