package dev.deal.embedding

import android.content.Context
import org.json.*
import java.io.File

/** Checks and lowers source against host-supplied declarations and the renderer pack. */
internal class ExperienceCompiler(private val context: Context, private val config: EmbeddingConfig, private val log: EmbeddingLog = EmbeddingLog(context)) {
    private val storage = ExperienceStorage(context, config.storageName)
    fun compile(dealSource: String, uiSource: String): File {
        check(dealSource.toByteArray().size <= 128 * 1024 && uiSource.toByteArray().size <= 64 * 1024)
        val distribution = storage.distribution()
        System.setProperty("deal.home", distribution.absolutePath)
        val project = storage.candidateDirectory()
        try {
            val sources = File(project, "src").apply { mkdirs() }
            val app = File(sources, "${config.sourceName}.deal").apply { writeText(dealSource) }
            val view = File(sources, "${config.sourceName}.dealui").apply { writeText(uiSource) }
            val packSource = context.assets.open("embedding/platform.dealui-pack").bufferedReader().use { it.readText() }
            val pack = File(project, "platform.dealui-pack").apply { writeText(packSource) }
            val parsedPack = deal.ui.UiParser.parsePack(pack.toPath(), packSource)
            RenderComponent.verify(parsedPack)
            val generated = log.stage("compiler-ui", target = "Deal UI check/lower", detail = JSONObject().put("deal", dealSource).put("dealui", uiSource).toString()) {
                deal.ui.UiSourceGenerator.generate(view.toPath(), uiSource, app.toPath(), dealSource, mapOf("./platform.dealui-pack" to parsedPack), distribution.toPath())
            }
            check(generated.checked().effectPolicies().isEmpty() && generated.checked().effectFailures().isEmpty()) { "This session host does not yet support effect policies or failure mappers" }
            val supported = RenderComponent.entries.map { it.capability }.toSet()
            check(generated.checked().metadata().componentCapabilities().values.all { it in supported }) { "Unsupported renderer capability" }
            app.writeText(deal.ui.UiSourceGenerator.compilerSource(dealSource) + generated.augmentation())
            // The entry must not share a name with the application module: the entry imports the
            // app module, so writing both to one path makes the view resolve the entry's exports.
            val entry = File(sources, ENTRY_NAME + ".deal").apply { writeText(generated.source()) }
            val externals = JSONObject()
            config.contracts.forEachIndexed { index, module ->
                val declaration = "host-$index.d.deal"
                File(project, declaration).writeText(dev.deal.embedding.capabilities.CapabilityBindings.declaration(module))
                externals.put(module.module, JSONObject().put("declaration", declaration))
            }
            File(project, "deal.json").writeText(JSONObject().put("languageVersion", "1.2").put("backend", "js")
                .put("moduleRoots", JSONArray(listOf("src"))).put("externals", externals).toString())
            val output = File(project, "output")
            val diagnostics = File(project, "diagnostics.json")
            log.stage("compiler-deal", target = "DEAL compile js", detail = entry.readText()) {
                val status = deal.Main.run(arrayOf("compile", entry.absolutePath, "--backend", "js", "--output", output.absolutePath, "--diagnostics-json", diagnostics.absolutePath))
                log.event("compiler-deal", "diagnostics", code = "STATUS_$status", detail = if (diagnostics.exists()) diagnostics.readText().replace(project.absolutePath, "candidate") else null)
                classifyCompilerOutcome(status, if (diagnostics.exists()) diagnostics.readText() else null, app.absolutePath, project.absolutePath)
            }
            return output
        } catch (error: deal.ui.UiDiagnostic) {
            project.deleteRecursively()
            throw CandidateRejected(JSONArray().put(JSONObject().put("stage", "ui").put("code", error.code()).put("message", error.message)
                .put("file", error.file()?.fileName?.toString()).put("line", error.line()).put("column", error.column()).put("expected", error.expected()).put("actual", error.actual())).toString())
        } catch (error: Throwable) { project.deleteRecursively(); throw error }
    }
}

// A nonzero exit alone does not establish invalid generated source. Publication/I/O
// failures can also return 1, and backend defects must never trigger model repair.
internal fun classifyCompilerOutcome(status: Int, raw: String?, appPath: String, projectPath: String) {
    if (status == 0) return
    val report = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    val diagnostics = report?.optJSONArray("diagnostics")
    val errors = diagnostics?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("severity") == "error" } } ?: emptyList()
    val repairable = status == 1 && report?.optInt("version") == 1 && errors.isNotEmpty() && errors.all { diagnostic ->
        val code = diagnostic.optString("code")
        val file = diagnostic.optJSONObject("range")?.optString("file")
        file == appPath && (code.matches(Regex("E[1-5][0-9]{3}")) || code == "E6006") && code != "E2010"
    }
    if (!repairable) throw CompilerOperationFailed(status, errors.map { it.optString("code") }.filter { it.matches(Regex("E[0-9]{4}")) })
    throw CandidateRejected(raw!!.replace(projectPath + "/", "").take(16000))
}
internal class CompilerOperationFailed(val status: Int, val codes: List<String>) : IllegalStateException("Compiler operation failed (status=$status, codes=${codes.joinToString(",")})")

/** The compiled entry filename; the mount must load this exact module. */
internal const val ENTRY_NAME = "experience_entry"

internal class CandidateRejected(val diagnostics: String) : IllegalArgumentException("Generated candidate rejected: " + diagnostics.take(1000))
