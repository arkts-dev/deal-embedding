package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import dev.deal.embedding.capabilities.*
import java.io.File
import java.util.concurrent.TimeUnit

data class EmbeddingConfig(val sourceName: String, var contracts: List<CapabilityContract>, val storageName: String)
data class ExperienceSource(val deal: String, val ui: String)

/** Called serially by the host. Owns compilation, atomic mounting and sandbox lifetime. */
class ExperienceRuntime(private val context: Context, private val capabilities: CapabilityHost, private val config: EmbeddingConfig) : AutoCloseable {
    private var engine: JavaScriptSandbox? = null
    private var session: UiSession? = null
    private var output: File? = null
    private val storage = ExperienceStorage(context, config.storageName)
    internal fun sandbox(): JavaScriptSandbox = engine ?: JavaScriptSandbox.createConnectedInstanceAsync(context).get(10, TimeUnit.SECONDS).also { engine = it }
    private fun <T> guarded(live: Boolean = true, operation: () -> T): T = try { operation() } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException || (live && (it is java.util.concurrent.TimeoutException || it is androidx.javascriptengine.IsolateTerminatedException)) }) close()
        throw error
    }

    fun remembered(): ExperienceSource? {
        return storage.remembered()
    }

    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit): CheckedCandidate =
        guarded(live = false) { ExperienceGenerator(context, config.copy(contracts = config.contracts.toList()), sandbox()).generate(intent, disclosedContext, model, cancellation, progress) }
    fun activate(candidate: CheckedCandidate): JSONObject {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val snapshot = activateChecked(candidate.source, candidate.output, preserveState = false)
        candidate.consumed = true
        return snapshot
    }
    fun activate(source: ExperienceSource): JSONObject {
        val candidateOutput = ExperienceCompiler(context, config).compile(source.deal, source.ui)
        return activateChecked(source, candidateOutput)
    }
    internal fun activateChecked(source: ExperienceSource, candidateOutput: File, preserveState: Boolean = true): JSONObject = guarded(live = false) {
        var candidate: UiSession? = null
        try {
            val sandbox = sandbox()
            candidate = UiSession(context, capabilities, sandbox, config)
            val retainedState = guarded { session?.state() } // Still reject outstanding effects.
            val snapshot = candidate.mount(candidateOutput, if (preserveState) retainedState else null)
            storage.commit(source)
            session?.close()
            output?.parentFile?.deleteRecursively()
            capabilities.end(); capabilities.begin()
            session = candidate; output = candidateOutput
            snapshot
        } catch (error: Throwable) {
            candidate?.close(); candidateOutput.parentFile?.deleteRecursively()
            throw error
        }
    }
    fun dispatch(slot: Int, payload: String?): JSONObject? {
        guarded { session?.dispatch(slot, payload) }
        return poll()
    }
    fun poll(): JSONObject? = guarded { session?.poll() }
    override fun close() {
        session?.close(); engine?.close(); capabilities.end()
        output?.parentFile?.deleteRecursively()
        session = null; engine = null; output = null
    }
}
