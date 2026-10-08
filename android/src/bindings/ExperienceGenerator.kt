package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.*
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Flattens a record result into field name and DEAL type, so lowering reads real fields. */
private fun recordFields(type: dev.deal.embedding.capabilities.CapabilityType): List<Pair<String, String>> {
    if (type.kind == "array" && type.element?.kind == "record") return fieldsOf(type.element!!)
    if (type.kind == "record") return fieldsOf(type)
    return emptyList()
}
private fun fieldsOf(type: dev.deal.embedding.capabilities.CapabilityType): List<Pair<String, String>> =
    type.fields.map { (name, field) -> name to when (field.kind) { "int" -> "int"; "boolean" -> "boolean"; "null" -> "string"; else -> "string" } }

/** Generation diagnostics persist to app storage so a failure stays readable after logcat rolls. */
internal class GenerationTrace(private val context: android.content.Context) {
    private val file = java.io.File(context.filesDir, "generation-trace.txt")
    @Synchronized fun record(line: String) {
        if (file.length() > 512 * 1024) file.delete()
        runCatching { file.appendText(line + "\n") }
    }
}

class CheckedCandidate internal constructor(internal val source: ExperienceSource, internal val output: File, internal val revision: String, val attempts: Int) : AutoCloseable {
    internal var consumed = false
    override fun close() { if (!consumed) output.parentFile.deleteRecursively() }
}
internal fun catalogRevision(config: EmbeddingConfig): String = MessageDigest.getInstance("SHA-256").digest(JSONArray(config.contracts.map { it.json() }).toString().toByteArray()).joinToString("") { "%02x".format(it) }

/** Runs compiled trusted DEAL. No native retry loop, and no capability invocation during inference. */
internal class ExperienceGenerator(private val context: Context, private val config: EmbeddingConfig, private val engine: JavaScriptSandbox) {
    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit): CheckedCandidate {
        require(intent.isNotBlank() && intent.length <= 4096 && disclosedContext.length <= 8192)
        val revision = catalogRevision(config)
        val catalog = JSONArray(config.contracts.map { it.json() }).toString()
        val catalogJson = catalog
        fun adapters(): List<ChoiceCatalogue.Adapter> = config.contracts.flatMap { contract ->
            contract.functions.map { function ->
                ChoiceCatalogue.Adapter(contract.module, function.name, function.parameters.map { it.name }, recordFields(function.result))
            }
        }
        fun requirementOf(text: String): String = text.take(160)
        val checked = mutableMapOf<String, Pair<ExperienceSource, File>>()
        val trace = GenerationTrace(context)
        trace.record("--- generation start ---")
        val program = SandboxProgram(engine, 512 * 1024)
        fun eval(s: String) = program.evaluate(s)
        try {
            val bundle = SandboxBundle.build(context, listOf("generation-contracts.js", "generation-session.js"), "",
                "runGeneration(factories, ${JSONObject().put("intent", intent).put("context", disclosedContext)})", assetRoot = "generation")
            eval(bundle)
            var attempts = 0
            while (true) {
                cancellation.check()
                val snapshot = JSONObject(eval("generationSnapshot()"))
                if (snapshot.getBoolean("done")) {
                    check(snapshot.optString("error").isEmpty()) { snapshot.optString("error") }
                    val result = snapshot.getJSONObject("result")
                    val id = result.getString("candidateId")
                    cancellation.check()
                    val candidate = checked.remove(id) ?: error("Generation exhausted repairs: ${result.optString("diagnostics").take(4000)}")
                    return CheckedCandidate(candidate.first, candidate.second, revision, result.getInt("attempts"))
                }
                val requests = JSONArray(eval("dealCapabilities.take()"))
                for (i in 0 until requests.length()) {
                    cancellation.check()
                    val request = requests.getJSONObject(i); val args = request.getJSONArray("args")
                    val value: Any = when(request.getString("module")) {
                        "embedding/discovery" -> catalog
                        "embedding/chooser" -> {
                            progress("Choosing a workspace shape")
                            try {
                                val adapters = adapters()
                                val issued = ChoiceCatalogue.issued(catalogJson, adapters)
                                val answer = model.complete(args.getString(0) + "\n\nISSUED OPTIONS\n" + issued.toString(), "", "", cancellation)
                                trace.record("choice answer: " + answer)
                                val (selection, problem) = ChoiceCatalogue.validate(answer) ?: (null to "invalid")
                                if (selection == null) {
                                    trace.record("choice not usable: " + problem)
                                    JSONObject().put("accepted", false).put("selection", "").put("diagnostics", problem).toString()
                                } else {
                                    // A read adapter must be callable without input; only prepare adapters take arguments.
                                    val read = adapters.firstOrNull { it.function != "outcome" && it.arguments.isEmpty() && it.fields.size >= 3 }
                                    val prepare = adapters.firstOrNull { it.function.startsWith("propose") || it.function == "stage" }
                                    trace.record("choice lowered for " + selection + " read=" + read?.module + "." + read?.function + " prepare=" + prepare?.module + "." + prepare?.function)
                                    val source = ChoiceCatalogue.lower(config.sourceName, selection, read, prepare, requirementOf(intent))
                                    trace.record("lowered deal:\n" + source.deal)
                                    trace.record("lowered dealui:\n" + source.ui)
                                    JSONObject().put("accepted", true).put("selection", JSONObject().put("deal", source.deal).put("dealui", source.ui).toString()).put("diagnostics", "").toString()
                                }
                            } catch (error: Exception) {
                                trace.record("choice failed: " + error.message)
                                JSONObject().put("accepted", false).put("selection", "").put("diagnostics", error.message?.take(400) ?: "Choice failed").toString()
                            }
                        }
                        "embedding/model" -> {
                            attempts++
                            progress("Generating source · attempt $attempts of 3")
                            val raw = model.complete(args.getString(0), args.getString(1), args.getString(2), cancellation)
                            trace.record("source attempt " + attempts + " response:\n" + raw)
                            raw
                        }
                        "embedding/checker" -> {
                            progress("Checking generated source")
                            try {
                                val raw = args.getString(0)
                                val envelope = runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Envelope is not a JSON object") }
                                require(envelope.length() == 2) { "Envelope must contain exactly deal and dealui; found " + envelope.keys().asSequence().sorted().joinToString(", ") }
                                val source = ExperienceSource(envelope.getString("deal"), envelope.getString("dealui"))
                                val output = ExperienceCompiler(context, config).compile(source.deal, source.ui)
                                val id = UUID.randomUUID().toString(); checked[id] = source to output
                                JSONObject().put("accepted", true).put("candidateId", id).put("diagnostics", "")
                            } catch (error: Exception) {
                                val diagnostics = if (error is CandidateRejected) error.diagnostics else JSONArray().put(JSONObject().put("stage", "envelope-or-host").put("code", "INVALID_CANDIDATE").put("message", error.message?.take(1000))).toString()
                                trace.record("checker rejected:\n" + diagnostics)
                                JSONObject().put("accepted", false).put("candidateId", "").put("diagnostics", diagnostics)
                            }
                        }
                        else -> error("Untrusted generation request")
                    }
                    cancellation.check()
                    eval("dealCapabilities.deliver(${JSONArray().put(JSONObject().put("id", request.getInt("id")).put("ok", true).put("value", value))})")
                }
                if (requests.length() == 0) Thread.sleep(10)
            }
        } finally { program.close(); checked.values.forEach { it.second.parentFile.deleteRecursively() } }
    }
}
