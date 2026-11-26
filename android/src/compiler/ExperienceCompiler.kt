package dev.deal.embedding

import android.content.Context
import org.json.*
import java.io.File

/** Checks and lowers source against host-supplied declarations and the renderer pack. */
internal class ExperienceCompiler(private val context: Context, private val config: RuntimeConfig, private val log: EmbeddingLog = EmbeddingLog(context)) {
    private val storage = ExperienceStorage(context, config.storageName)
    fun compile(dealSource: String, uiSource: String, target: String?, composition: String, fresh: Boolean): File {
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
            // The host-timezone day mechanism is a checked module of composed source, not a provider:
            // its declaration compiles like the graphics declarations and the isolate supplies the reading.
            File(project,"day.d.deal").writeText(context.assets.open("protocol/day.d.deal").bufferedReader().use { it.readText() })
            externals.put("embedding/day",JSONObject().put("declaration","day.d.deal"))
            // The public-probe mechanism is a checked module of composed source beside the day mechanism:
            // its declaration compiles like the graphics declarations and the isolate supplies the binding.
            File(project,"probe.d.deal").writeText(context.assets.open("protocol/probe.d.deal").bufferedReader().use { it.readText() })
            externals.put("embedding/probe",JSONObject().put("declaration","probe.d.deal"))
            // The host Session facts compiled admission re-reads are a frozen host module, so the
            // emitted provider import compiles exactly as it does in the deterministic lanes.
            File(project,"session.d.deal").writeText(context.assets.open("generation/session.d.deal").bufferedReader().use { it.readText() })
            externals.put("embedding/session",JSONObject().put("declaration","session.d.deal"))
            // Deal UI's framework-owned store: the emitted effect-bound calls compile against the same
            // declaration the packaged renderer compiles against, so the module resolves instead of
            // failing as an unknown external.
            val storageDeclaration = File(distribution, "ui/host/storage.d.deal")
            check(storageDeclaration.isFile) { "Deal UI storage host declaration is missing: " + storageDeclaration }
            File(project, "storage.d.deal").writeText(storageDeclaration.readText())
            externals.put("host/storage", JSONObject().put("declaration", "storage.d.deal"))
            config.contracts.forEachIndexed { index, module ->
                val declaration = "host-$index.d.deal"
                File(project, declaration).writeText(dev.deal.embedding.capabilities.CapabilityBindings.declaration(module))
                externals.put(module.module, JSONObject().put("declaration", declaration))
            }
            File(project, "deal.json").writeText(JSONObject().put("languageVersion", "1.6").put("backend", "js")
                .put("moduleRoots", JSONArray(listOf("src"))).put("externals", externals).toString())
            // The issued assembly metadata is private compiler metadata: admission cross-checks it against
            // the compiled AppState declarations below, so a removed or changed fact rejects rather than
            // falling back to a source scan. Nothing of it enters generated source.
            val compositionJson = if (composition.isBlank()) JSONObject() else JSONObject(composition)
            val declarations = declaredFields(app.readText())
            val output = File(project, "output")
            val diagnostics = File(project, "diagnostics.json")
            log.stage("compiler-deal", target = "DEAL compile js", detail = entry.readText()) {
                val phases = JSONObject()
                val status = deal.module.CompilationTiming.observe { phase, nanos -> phases.put(phase, nanos / 1000000.0) }.use {
                    deal.ui.UiCheckpointCompiler.compile(entry.toPath(), output.toPath(), diagnostics.toPath())
                }
                log.event("compiler-deal", "phases", target = "Monotonic phase milliseconds; codegen-total includes lowering/emission", detail = phases.toString())
                log.event("compiler-deal", "diagnostics", code = "STATUS_$status", detail = if (diagnostics.exists()) diagnostics.readText() else null)
                classifyCompilerOutcome(status, if (diagnostics.exists()) diagnostics.readText() else null, app.absolutePath, project.absolutePath)
            }
            File(output,"admission.json").writeText(JSONObject().put("imports",imports).put("providers",JSONArray(config.contracts.map{it.module}))
                .put("packDigests",JSONArray(metadata.packDigests().values.toList())).put("packVersions",JSONArray(metadata.packVersions().values.toList()))
                .put("issuedPackDigest",parsedPack.digest()).put("issuedPackVersion",parsedPack.version())
                .put("propConstraints",JSONObject(context.assets.open("embedding/prop-constraints.json").bufferedReader().use{it.readText()}))
                .put("cardProfile",JSONObject(context.assets.open("embedding/card-profile.json").bufferedReader().use{it.readText()}))
                .put("declarations",JSONArray(declarations)).put("fresh",fresh)
                .also { if(!composition.isBlank()) it.put("composition",compositionJson) }
                .put("target",issuedTarget).toString())
            return output
        } catch (error: deal.ui.UiDiagnostic) {
            val sources = mapOf(
                File(project, "src/${config.sourceName}.deal").absolutePath to dealSource,
                File(project, "src/${config.sourceName}.dealui").absolutePath to uiSource
            )
            try {
                val ownedFile = error.file()?.toAbsolutePath()?.normalize()
                if (ownedFile == null || sources.keys.none { File(it).toPath() == ownedFile }) {
                    throw CompilerOperationFailed(1, listOf(error.code()))
                }
                classifyCompilerOutcome(1, error.toJson(project.toPath(), sources), ownedFile.toString(), project.absolutePath)
                error("A rejected UI diagnostic cannot be accepted")
            } catch (failure: Throwable) {
                runCatching { StagingReclamation.remove(context, project) }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        } catch (error: Throwable) { runCatching{StagingReclamation.remove(context,project)}.onFailure{error.addSuppressed(it)};throw error }
    }
}

/** The experience's frozen target is a compile input; nothing here supplies, derives or promotes one. */
private fun issueTarget(target: String?): String = CompileInput.target(target) ?: throw CandidateRejected(JSONArray().put(JSONObject().put("stage", "admission").put("code", "ADMISSION_TARGET")
    .put("message", "A compile input requires the experience's frozen target").put("expected", "screen|chat|card")
    .put("actual", target ?: "absent")).toString())

/** The compiled AppState declarations, read from the parsed emitted source; admission compares them with the issued composition metadata. */
internal fun declaredFields(source: String): List<String> {
    val parsed = deal.parser.Parser(deal.lexer.Lexer(source, "experience.deal").tokenize().tokens(), "experience.deal").parse()
    val names = mutableListOf<String>()
    for (statement in parsed.program().statements()) {
        val declaration = when (statement) {
            is deal.ast.ExportDeclaration -> statement.declaration()
            else -> statement
        }
        if (declaration is deal.ast.ClassDeclaration && declaration.name() == "AppState") {
            for (field in declaration.fields()) names.add(field.name())
        }
    }
    return names
}

/** The compiled entry filename; the mount must load this exact module. */
internal const val ENTRY_NAME = "experience_entry"

internal class CandidateRejected(val diagnostics: String) : IllegalArgumentException("Generated candidate rejected: " + diagnostics.take(1000))
