package dev.deal.embedding

import android.content.Context
import org.json.*
import java.io.File

/** Checks and lowers source against host-supplied declarations and the renderer pack. */
internal class ExperienceCompiler(private val context: Context, private val config: RuntimeConfig, private val log: EmbeddingLog = EmbeddingLog(context)) {
    private val storage = ExperienceStorage(context, config.storageName)
    fun compile(dealSource: String, uiSource: String, target: String?): File {
        // The frozen target is carried, never chosen: an absent or out-of-set value rejects before a manifest exists.
        val issuedTarget = issueTarget(target)
        StagingReclamation.retry(context)
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
            val imports=JSONArray(FragmentMechanisms.invoke("imports",JSONObject().put("source",deal.ui.UiSourceGenerator.compilerSource(dealSource)).toString()))
            val generated = log.stage("compiler-ui", target = "Deal UI check/lower", detail = JSONObject().put("deal", dealSource).put("dealui", uiSource).toString()) {
                deal.ui.UiSourceGenerator.generate(view.toPath(), uiSource, app.toPath(), dealSource, mapOf("./platform.dealui-pack" to parsedPack), distribution.toPath(), mapOf("embedding/graphics" to context.assets.open("protocol/graphics.d.deal").bufferedReader().use { it.readText() }))
            }
            val metadata=generated.checked().metadata()
            runCatching { PayloadConsumption.verify(generated.checked(), parsedPack) }.onFailure { error ->
                throw CandidateRejected(JSONArray().put(JSONObject().put("stage", "ui").put("code", "PAYLOAD_UNCONSUMED")
                    .put("message", error.message ?: "A payload event must consume its payload")).toString())
            }
            check(generated.checked().effectPolicies().isEmpty() && generated.checked().effectFailures().isEmpty()) { "This session host does not yet support effect policies or failure mappers" }
            val supported = RenderComponent.entries.map { it.capability }.toSet()
            check(generated.checked().metadata().componentCapabilities().values.all { it in supported }) { "Unsupported renderer capability" }
            app.writeText(deal.ui.UiSourceGenerator.compilerSource(dealSource) + generated.augmentation())
            // The entry must not share a name with the application module: the entry imports the
            // app module, so writing both to one path makes the view resolve the entry's exports.
            val entry = File(sources, ENTRY_NAME + ".deal").apply { writeText(generated.source()) }
            val externals = JSONObject()
            File(project,"graphics.d.deal").writeText(context.assets.open("protocol/graphics.d.deal").bufferedReader().use { it.readText() })
            externals.put("embedding/graphics",JSONObject().put("declaration","graphics.d.deal"))
            config.contracts.forEachIndexed { index, module ->
                val declaration = "host-$index.d.deal"
                File(project, declaration).writeText(dev.deal.embedding.capabilities.CapabilityBindings.declaration(module))
                externals.put(module.module, JSONObject().put("declaration", declaration))
            }
            File(project, "deal.json").writeText(JSONObject().put("languageVersion", "1.6").put("backend", "js")
                .put("moduleRoots", JSONArray(listOf("src"))).put("externals", externals).toString())
            val output = File(project, "output")
            val diagnostics = File(project, "diagnostics.json")
            log.stage("compiler-deal", target = "DEAL compile js", detail = entry.readText()) {
                val phases = JSONObject()
                val status = deal.module.CompilationTiming.observe { phase, nanos -> phases.put(phase, nanos / 1000000.0) }.use {
                    deal.ui.UiCheckpointCompiler.compile(entry.toPath(), output.toPath(), diagnostics.toPath())
                }
                log.event("compiler-deal", "phases", target = "Monotonic phase milliseconds; codegen-total includes lowering/emission", detail = phases.toString())
                log.event("compiler-deal", "diagnostics", code = "STATUS_$status", detail = if (diagnostics.exists()) diagnostics.readText().replace(project.absolutePath, "candidate") else null)
                classifyCompilerOutcome(status, if (diagnostics.exists()) diagnostics.readText() else null, app.absolutePath, project.absolutePath)
            }
            File(output,"admission.json").writeText(JSONObject().put("imports",imports).put("providers",JSONArray(config.contracts.map{it.module}))
                .put("packDigests",JSONArray(metadata.packDigests().values.toList())).put("packVersions",JSONArray(metadata.packVersions().values.toList()))
                .put("issuedPackDigest",parsedPack.digest()).put("issuedPackVersion",parsedPack.version())
                .put("propConstraints",JSONObject(context.assets.open("embedding/prop-constraints.json").bufferedReader().use{it.readText()}))
                .put("cardProfile",JSONObject(context.assets.open("embedding/card-profile.json").bufferedReader().use{it.readText()}))
                .put("target",issuedTarget).toString())
            return output
        } catch (error: deal.ui.UiDiagnostic) {
            val producer = error.compilerDiagnostic()
            if (producer != null && (deal.diagnostics.DiagnosticClassification.forCode(producer.code()).responsibility() !=
                    deal.diagnostics.DiagnosticClassification.Responsibility.SOURCE_CONTRACT ||
                    producer.range().origin() != deal.diagnostics.RangeOrigin.SOURCE ||
                    producer.range().file() != File(project, "src/${config.sourceName}.deal").absolutePath)) {
                val failure = CompilerOperationFailed(1, listOf(producer.code()))
                runCatching { StagingReclamation.remove(context, project) }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
            val evidence = if (producer != null) {
                deal.diagnostics.DiagnosticStructuredOutput.toJson(listOf(producer))
            } else {
                JSONObject().put("version", 2).put("diagnostics", JSONArray().put(JSONObject()
                    .put("stage", "ui").put("code", error.code()).put("message", error.message)
                    .put("range", JSONObject().put("file", error.file()?.fileName?.toString())
                        .put("startLine", error.line()).put("startColumn", error.column())
                        .put("endLine", error.endLine()).put("endColumn", error.endColumn() + 1))
                    .put("expected", error.expected()).put("actual", error.actual())
                    .put("notes", JSONArray(error.notes().map { note -> JSONObject().put("message", note.message()) })))).toString()
            }
            val rejection=CandidateRejected(evidence.replace(project.absolutePath + "/", ""))
            runCatching{StagingReclamation.remove(context,project)}.onFailure{rejection.addSuppressed(it)}
            throw rejection
        } catch (error: Throwable) { runCatching{StagingReclamation.remove(context,project)}.onFailure{error.addSuppressed(it)};throw error }
    }
}

/** The experience's frozen target is a compile input; nothing here supplies, derives or promotes one. */
private fun issueTarget(target: String?): String = CompileInput.target(target) ?: throw CandidateRejected(JSONArray().put(JSONObject().put("stage", "admission").put("code", "ADMISSION_TARGET")
    .put("message", "A compile input requires the experience's frozen target").put("expected", "screen|chat|card")
    .put("actual", target ?: "absent")).toString())

/** The compiled entry filename; the mount must load this exact module. */
internal const val ENTRY_NAME = "experience_entry"

internal class CandidateRejected(val diagnostics: String) : IllegalArgumentException("Generated candidate rejected: " + diagnostics.take(1000))
