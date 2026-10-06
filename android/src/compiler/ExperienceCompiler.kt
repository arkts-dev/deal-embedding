package dev.deal.embedding

import android.content.Context
import org.json.*
import java.io.File

/** Checks and lowers source against host-supplied declarations and the renderer pack. */
internal class ExperienceCompiler(private val context: Context, private val config: EmbeddingConfig) {
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
            val generated = deal.ui.UiSourceGenerator.generate(view.toPath(), uiSource, app.toPath(), dealSource,
                mapOf("./platform.dealui-pack" to parsedPack), distribution.toPath())
            check(generated.checked().effectPolicies().isEmpty() && generated.checked().effectFailures().isEmpty()) { "This session host does not yet support effect policies or failure mappers" }
            val supported = RenderComponent.entries.map { it.capability }.toSet()
            check(generated.checked().metadata().componentCapabilities().values.all { it in supported }) { "Unsupported renderer capability" }
            app.writeText(deal.ui.UiSourceGenerator.compilerSource(dealSource) + generated.augmentation())
            val entry = File(sources, "experience.deal").apply { writeText(generated.source()) }
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
            val status = deal.Main.run(arrayOf("compile", entry.absolutePath, "--backend", "js", "--output", output.absolutePath, "--diagnostics-json", diagnostics.absolutePath))
            if (status != 0) throw CandidateRejected(if (diagnostics.exists()) diagnostics.readText().replace(project.absolutePath + "/", "").take(16000) else "[]")
            return output
        } catch (error: deal.ui.UiDiagnostic) {
            project.deleteRecursively()
            throw CandidateRejected(JSONArray().put(JSONObject().put("stage", "ui").put("code", error.code()).put("message", error.message)
                .put("file", error.file()?.fileName?.toString()).put("line", error.line()).put("column", error.column()).put("expected", error.expected()).put("actual", error.actual())).toString())
        } catch (error: Throwable) { project.deleteRecursively(); throw error }
    }
}

internal class CandidateRejected(val diagnostics: String) : IllegalArgumentException("Generated candidate rejected")
