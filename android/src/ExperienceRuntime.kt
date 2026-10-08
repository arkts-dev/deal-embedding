package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import dev.deal.embedding.capabilities.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EmbeddingConfig(val sourceName: String, var contracts: List<CapabilityContract>, val storageName: String)
data class ExperienceSource(val deal: String, val ui: String)

/** A live workspace: its own isolate and state, sharing the connected sandbox engine. */
class LiveWorkspace internal constructor(val id: String, val title: String, internal val session: UiSession, internal val output: File, internal val source: ExperienceSource)

/**
 * Called serially by the host. Owns compilation, atomic mounting, live workspaces and the shared engine.
 * Several workspaces stay mounted at once; each keeps its own isolate and its own capability session.
 */
class ExperienceRuntime(private val context: Context, private val capabilities: CapabilityHost, private val config: EmbeddingConfig) : AutoCloseable {
    private var engine: JavaScriptSandbox? = null
    private var session: UiSession? = null
    private var output: File? = null
    private val workspaces = linkedMapOf<String, LiveWorkspace>()
    private val storage = ExperienceStorage(context, config.storageName)
    internal fun sandbox(): JavaScriptSandbox = engine ?: JavaScriptSandbox.createConnectedInstanceAsync(context).get(10, TimeUnit.SECONDS).also { engine = it }
    private fun <T> guarded(live: Boolean = true, operation: () -> T): T = try { operation() } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException || (live && (it is java.util.concurrent.TimeoutException || it is androidx.javascriptengine.IsolateTerminatedException)) }) close()
        throw error
    }

    fun remembered(): ExperienceSource? = storage.remembered()

    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit): CheckedCandidate =
        guarded(live = false) { ExperienceGenerator(context, config.copy(contracts = config.contracts.toList()), sandbox()).generate(intent, disclosedContext, model, cancellation, progress) }
    fun activate(candidate: CheckedCandidate): JSONObject {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val snapshot = activateChecked(candidate.source, candidate.output, preserveState = false, receipts = candidate.receipts, identity = candidate.identity)
        candidate.consumed = true
        return snapshot
    }
    /** Check saved or host-supplied source without inference; activation remains a separate decision. */
    fun check(source: ExperienceSource): CheckedCandidate = guarded(live = false) {
        val receipts = StageReceipts(context)
        val identity = UUID.randomUUID().toString()
        receipts.event("route", "saved", candidate = identity)
        CheckedCandidate(source, receipts.stage("check", candidate = identity) { ExperienceCompiler(context, config).compile(source.deal, source.ui) }, catalogRevision(config), 0, receipts, identity)
    }
    fun activate(source: ExperienceSource): JSONObject {
        val candidateOutput = ExperienceCompiler(context, config).compile(source.deal, source.ui)
        return activateChecked(source, candidateOutput)
    }
    internal fun activateChecked(source: ExperienceSource, candidateOutput: File, preserveState: Boolean = true, receipts: StageReceipts = StageReceipts(context), identity: String = UUID.randomUUID().toString()): JSONObject = guarded(live = false) {
        var candidate: UiSession? = null
        try {
            val sandbox = sandbox()
            candidate = UiSession(context, capabilities, sandbox, config, receipts, identity)
            val retainedState = guarded { session?.state() } // Still reject outstanding effects.
            val snapshot = receipts.stage("mount", candidate = identity) { candidate.mount(candidateOutput, if (preserveState) retainedState else null) }
            storage.commit(source)
            session?.close()
            output?.parentFile?.deleteRecursively()
            session = candidate; output = candidateOutput
            snapshot
        } catch (error: Throwable) {
            candidate?.close(); candidateOutput.parentFile?.deleteRecursively()
            throw error
        }
    }

    /** Mount a workspace with its own capability session and return its initial snapshot. */
    fun open(title: String, candidate: CheckedCandidate): Pair<LiveWorkspace, JSONObject> = guarded(live = false) {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val id = UUID.randomUUID().toString()
        val live = LiveWorkspace(id, title, UiSession(context, capabilities, sandbox(), config, candidate.receipts, candidate.identity, id), candidate.output, candidate.source)
        try {
            val snapshot = candidate.receipts.stage("mount", candidate.identity, id) { live.session.mount(candidate.output) }
            candidate.consumed = true
            workspaces[id] = live
            storage.saveWorkspace(id, title, candidate.source)
            live to snapshot
        } catch (error: Throwable) { live.session.close(); candidate.output.parentFile?.deleteRecursively(); throw error }
    }
    /** Native host acknowledges publication separately from runtime snapshot production. */
    fun published(id: String, version: Int) {
        val live = workspaces[id] ?: return
        live.session.published(version)
    }
    fun workspaces(): List<LiveWorkspace> = workspaces.values.toList()
    fun workspace(id: String): LiveWorkspace? = workspaces[id]
    fun dispatch(id: String, slot: Int, payload: String?): JSONObject? { val live = workspaces[id] ?: return null; guarded { live.session.dispatch(slot, payload) }; return poll(id) }
    fun poll(id: String): JSONObject? = guarded { workspaces[id]?.session?.poll() }
    fun closeWorkspace(id: String) {
        val live = workspaces.remove(id) ?: return
        live.session.close(); live.output.parentFile?.deleteRecursively(); storage.forgetWorkspace(id)
    }

    fun dispatch(slot: Int, payload: String?): JSONObject? {
        guarded { session?.dispatch(slot, payload) }
        return poll()
    }
    fun poll(): JSONObject? = guarded { session?.poll() }
    override fun close() {
        workspaces.values.toList().forEach { closeWorkspace(it.id) }
        session?.close(); engine?.close()
        output?.parentFile?.deleteRecursively()
        session = null; engine = null; output = null
    }
}
