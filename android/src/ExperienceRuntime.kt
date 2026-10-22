package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import dev.deal.embedding.capabilities.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EmbeddingConfig(val sourceName: String, var contracts: List<CapabilityContract>, val storageName: String, val generationAssets: String = "generation", val policyAssets: String = "", val retainState: Boolean = false) {
}
internal data class ExperienceSource(val deal: String, val ui: String)
internal data class SavedWorkspace(val id: String, val title: String, val origin: WorkspaceOrigin, val attempts: Int)

/** A live workspace: its own isolate and state, sharing the connected sandbox engine. */
internal class LiveWorkspace internal constructor(val id: String, val title: String, internal val session: UiSession, internal val output: File, internal val source: ExperienceSource, val origin: WorkspaceOrigin, val attempts: Int)

/**
 * Serialized by embedding mechanisms. Owns compilation, atomic mounting, live workspaces and the shared engine.
 * Several workspaces stay mounted at once; each keeps its own isolate and its own capability session.
 */
internal class ExperienceRuntime(private val context: Context, private val capabilities: CapabilityHost, private val config: EmbeddingConfig) : AutoCloseable {
    private var engine: JavaScriptSandbox? = null
    private val workspaces = linkedMapOf<String, LiveWorkspace>()
    private val storage = ExperienceStorage(context, config.storageName)
    internal fun sandbox(): JavaScriptSandbox = engine ?: SandboxEngine.acquire(context).also { engine = it }
    private fun <T> guarded(live: Boolean = true, operation: () -> T): T = try { operation() } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException || (live && (it is java.util.concurrent.TimeoutException || it is androidx.javascriptengine.IsolateTerminatedException || it is ClosedSandboxProgram)) }) {
            val sandboxDead = generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException }
            val active=engine;engine=null
            runCatching{active?.let { SandboxEngine.release(it, dead = sandboxDead) }}.onFailure{error.addSuppressed(it)}
            runCatching{close()}.onFailure{error.addSuppressed(it)}
        }
        throw error
    }

    /** Check saved or host-supplied source without inference; activation remains a separate decision. */
    /** Provenance is trusted host metadata, never an assertion from application source. */
    fun check(source: ExperienceSource, origin: WorkspaceOrigin = WorkspaceOrigin.SAVED_SOURCE, attempts: Int = 0): CheckedCandidate = guarded(live = false) {
        val log = EmbeddingLog(context)
        val identity = UUID.randomUUID().toString()
        log.event("route", "saved", candidate = identity)
        CheckedCandidate(context,source, log.operation("check", candidate = identity) { child -> ExperienceCompiler(context, config, child).compile(source.deal, source.ui) }, catalogRevision(config), attempts, log, identity, origin)
    }
    /** Mount a workspace with its own capability session and return its initial snapshot. */
    fun open(title: String, candidate: CheckedCandidate, id: String, session:String): Pair<LiveWorkspace, JSONObject> = mountWorkspace(id, title, candidate,session)

    /** Explicit reopen rechecks saved source against current contracts; never invokes inference. */
    fun savedWorkspaces(): List<SavedWorkspace> = storage.savedWorkspaces()
    fun reopen(id: String, session:String): Pair<LiveWorkspace, JSONObject> {
        workspaces[id]?.let { return it to it.session.advance() }
        val saved = savedWorkspaces().firstOrNull { it.id == id } ?: error("Saved workspace not found")
        val source = storage.source(id) ?: error("Saved workspace source missing")
        return check(source, saved.origin, saved.attempts).use { mountWorkspace(id, saved.title, it,session) }
    }
    fun forgetWorkspace(id: String) { closeWorkspace(id); storage.forgetWorkspace(id); EmbeddingLog(context).event("storage", "deleted", workspace = id) }
    private fun mountWorkspace(id: String, title: String, candidate: CheckedCandidate,session:String): Pair<LiveWorkspace, JSONObject> = guarded(live = false) {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val live = LiveWorkspace(id, title, UiSession(context, capabilities, sandbox(), config, candidate.log.bind(id,session), candidate.identity, id), candidate.output, candidate.source, candidate.origin, candidate.attempts)
        try {
            val restored = if (config.retainState) storage.state(id, candidate.source) else null
            val snapshot = candidate.log.stage("mount", candidate.identity, id) { live.session.mount(candidate.output, restored) }
            storage.saveWorkspace(id, title, candidate.source, candidate.origin, candidate.attempts)
            candidate.consumed = true
            workspaces[id] = live
            live to snapshot
        } catch (error: Throwable) { runCatching{live.session.close()}.onFailure{error.addSuppressed(it)};throw error }
    }
    /** Native host acknowledges publication separately from runtime snapshot production. */
    fun published(id: String, version: Int) {
        val live = workspaces[id] ?: return
        live.session.published(version)
    }
    fun workspaces(): List<LiveWorkspace> = workspaces.values.toList()
    fun workspace(id: String): LiveWorkspace? = workspaces[id]
    private fun checkpoint(live: LiveWorkspace) {
        if (config.retainState) live.session.state()?.let { state ->
            if (retainedStates[live.id] != state) { storage.saveState(live.id,live.source,state); retainedStates[live.id] = state }
        }
    }
    private val retainedStates = mutableMapOf<String,String>()
    fun dispatch(id: String, slot: Int, payload: String?) { val live = workspaces[id] ?: error("Closed session"); live.session.dispatch(slot, payload); checkpoint(live) }
    fun advance(id: String): JSONObject? = try { workspaces[id]?.let { live -> live.session.advance().also { checkpoint(live) } } } catch(error:Exception) {
        val dead=generateSequence<Throwable>(error){it.cause}.any{it is androidx.javascriptengine.SandboxDeadException}
        if(dead){
            val active=engine;engine=null
            runCatching{active?.let{SandboxEngine.release(it,dead=true)}}.onFailure{error.addSuppressed(it)}
            runCatching{close()}.onFailure{error.addSuppressed(it)}
        }else runCatching{closeWorkspace(id)}.onFailure{error.addSuppressed(it)}
        throw error
    }
    fun closeWorkspace(id: String) {
        val live = workspaces.remove(id) ?: return
        EmbeddingLog(context).event("session", "closed", workspace = id)
        var failure:Throwable?=null
        try{checkpoint(live)}catch(error:Throwable){failure=error}
        retainedStates.remove(id)
        try{live.session.close()}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        try{StagingReclamation.remove(context,live.output.parentFile)}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        failure?.let{throw it}
    }

    override fun close() {
        var failure:Throwable?=null
        workspaces.values.toList().forEach { try{closeWorkspace(it.id)}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)} }
        val active=engine;engine=null
        try{active?.let{SandboxEngine.release(it)}}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}
        failure?.let{throw it}
    }
}
