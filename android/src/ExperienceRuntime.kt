package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import dev.deal.embedding.capabilities.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EmbeddingConfig(val sourceName: String, var contracts: List<CapabilityContract>, val storageName: String, val repairStrategy: String = "full") {
    init { require(repairStrategy in setOf("full", "delta")) { "Unknown source repair strategy" } }
}
data class ExperienceSource(val deal: String, val ui: String)
data class SavedWorkspace(val id: String, val title: String, val origin: WorkspaceOrigin, val attempts: Int)

/** A live workspace: its own isolate and state, sharing the connected sandbox engine. */
class LiveWorkspace internal constructor(val id: String, val title: String, internal val session: UiSession, internal val output: File, internal val source: ExperienceSource, val origin: WorkspaceOrigin, val attempts: Int)

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
    internal fun sandbox(): JavaScriptSandbox = engine ?: SandboxEngine.acquire(context).also { engine = it }
    private fun <T> guarded(live: Boolean = true, operation: () -> T): T = try { operation() } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException || (live && (it is java.util.concurrent.TimeoutException || it is androidx.javascriptengine.IsolateTerminatedException)) }) {
            engine?.let { SandboxEngine.release(it, dead = true) }; engine = null
            close()
        }
        throw error
    }

    fun remembered(): ExperienceSource? = storage.remembered()

    fun generate(intent: String, disclosedContext: String, model: ModelClient, cancellation: GenerationCancellation, progress: (String) -> Unit): CheckedCandidate =
        guarded(live = false) { ExperienceGenerator(context, config.copy(contracts = config.contracts.toList()), sandbox()).generate(intent, disclosedContext, model, cancellation, progress) }
    fun activate(candidate: CheckedCandidate): JSONObject {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val snapshot = activateChecked(candidate.source, candidate.output, preserveState = false, log = candidate.log, identity = candidate.identity)
        candidate.consumed = true
        return snapshot
    }
    /** Check saved or host-supplied source without inference; activation remains a separate decision. */
    /** Provenance is trusted host metadata, never an assertion from application source. */
    fun check(source: ExperienceSource, origin: WorkspaceOrigin = WorkspaceOrigin.SAVED_SOURCE, attempts: Int = 0): CheckedCandidate = guarded(live = false) {
        val log = EmbeddingLog(context)
        val identity = UUID.randomUUID().toString()
        log.event("route", "saved", candidate = identity)
        CheckedCandidate(source, log.operation("check", candidate = identity) { child -> ExperienceCompiler(context, config, child).compile(source.deal, source.ui) }, catalogRevision(config), attempts, log, identity, origin)
    }
    fun activate(source: ExperienceSource): JSONObject {
        val candidateOutput = ExperienceCompiler(context, config).compile(source.deal, source.ui)
        return activateChecked(source, candidateOutput)
    }
    internal fun activateChecked(source: ExperienceSource, candidateOutput: File, preserveState: Boolean = true, log: EmbeddingLog = EmbeddingLog(context), identity: String = UUID.randomUUID().toString()): JSONObject = guarded(live = false) {
        var candidate: UiSession? = null
        try {
            val sandbox = sandbox()
            candidate = UiSession(context, capabilities, sandbox, config, log, identity)
            val retainedState = guarded { session?.state() } // Still reject outstanding effects.
            val snapshot = log.stage("mount", candidate = identity) { candidate.mount(candidateOutput, if (preserveState) retainedState else null) }
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
    fun open(title: String, candidate: CheckedCandidate): Pair<LiveWorkspace, JSONObject> = mountWorkspace(UUID.randomUUID().toString(), title, candidate)

    /** Explicit reopen rechecks saved source against current contracts; never invokes inference. */
    fun savedWorkspaces(): List<SavedWorkspace> = storage.savedWorkspaces()
    fun reopen(id: String): Pair<LiveWorkspace, JSONObject> {
        workspaces[id]?.let { return it to it.session.poll() }
        val saved = savedWorkspaces().firstOrNull { it.id == id } ?: error("Saved workspace not found")
        val source = storage.source(id) ?: error("Saved workspace source missing")
        return check(source, saved.origin, saved.attempts).use { mountWorkspace(id, saved.title, it) }
    }
    fun forgetWorkspace(id: String) { closeWorkspace(id); storage.forgetWorkspace(id); EmbeddingLog(context).event("storage", "deleted", workspace = id) }
    private fun mountWorkspace(id: String, title: String, candidate: CheckedCandidate): Pair<LiveWorkspace, JSONObject> = guarded(live = false) {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val live = LiveWorkspace(id, title, UiSession(context, capabilities, sandbox(), config, candidate.log, candidate.identity, id), candidate.output, candidate.source, candidate.origin, candidate.attempts)
        try {
            val snapshot = candidate.log.stage("mount", candidate.identity, id) { live.session.mount(candidate.output) }
            storage.saveWorkspace(id, title, candidate.source, candidate.origin, candidate.attempts)
            candidate.consumed = true
            workspaces[id] = live
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
        EmbeddingLog(context).event("session", "closed", workspace = id)
        live.session.close(); live.output.parentFile?.deleteRecursively()
    }

    fun dispatch(slot: Int, payload: String?): JSONObject? {
        guarded { session?.dispatch(slot, payload) }
        return poll()
    }
    fun poll(): JSONObject? = guarded { session?.poll() }
    override fun close() {
        workspaces.values.toList().forEach { closeWorkspace(it.id) }
        session?.close(); engine?.let { SandboxEngine.release(it) }
        output?.parentFile?.deleteRecursively()
        session = null; engine = null; output = null
    }
}
