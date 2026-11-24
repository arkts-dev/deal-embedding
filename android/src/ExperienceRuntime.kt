package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import dev.deal.embedding.capabilities.*
import java.io.File
import java.util.UUID

internal data class RuntimeConfig(val sourceName: String, var contracts: List<CapabilityContract>, val storageName: String, val generationAssets: String = "generation", val retainState: Boolean = false, val execution: ExecutionLimits = ExecutionLimits(), val target: String = "screen", val clockIntervalMs: Long = 1_000, val clockToleranceMs: Long = 2_000) {
}
internal data class ExperienceSource(val deal: String, val ui: String)
internal data class SavedWorkspace(val id: String, val title: String, val origin: WorkspaceOrigin, val attempts: Int, val target: String?)

/** Issued metadata only; suppressed preparation isolates are discarded before retention. */
internal class PreparedWorkspace(val token:String, val id:String, val title:String, val session:String, val candidate:CheckedCandidate, val trace:String, var persisted:Boolean=false)
internal class LiveWorkspace internal constructor(val id: String, val title: String, internal val session: UiSession, internal val output: File, internal val source: ExperienceSource, val origin: WorkspaceOrigin, val attempts: Int)

/**
 * Serialized by embedding mechanisms. Owns compilation, atomic mounting, live workspaces and the shared engine.
 * Several workspaces stay mounted at once; each keeps its own isolate and its own capability session.
 */
internal class ExperienceRuntime(private val context: Context, private val capabilities: CapabilityHost, private val config: RuntimeConfig) : AutoCloseable {
    private var engine: JavaScriptSandbox? = null
    private val workspaces = linkedMapOf<String, LiveWorkspace>()
    private val storage = ExperienceStorage(context, config.storageName)
    internal fun sandbox(): JavaScriptSandbox = engine ?: SandboxEngine.acquire(context).also { engine = it }
    private fun <T> guarded(live: Boolean = true, operation: () -> T): T = try { operation() } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException || (live && (it is java.util.concurrent.TimeoutException || it is androidx.javascriptengine.IsolateTerminatedException || it is ClosedSandboxProgram)) }) {
            val sandboxDead = generateSequence<Throwable>(error) { it.cause }.any { it is androidx.javascriptengine.SandboxDeadException || it is androidx.javascriptengine.MemoryLimitExceededException }
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
        CheckedCandidate(context,source, log.operation("check", candidate = identity) { child -> CandidateChecks(context,config,sandbox(),child).use { it.check(source) } }, catalogRevision(config), attempts, log, identity, origin)
    }
    fun savedWorkspaces(): List<SavedWorkspace> = storage.savedWorkspaces()
    fun forgetWorkspace(id: String) { closeWorkspace(id); storage.forgetWorkspace(id); EmbeddingLog(context).event("storage", "deleted", workspace = id) }
    private val prepared = mutableMapOf<String,PreparedWorkspace>()
    internal fun prepareWorkspace(id:String,title:String,candidate:CheckedCandidate,session:String):PreparedWorkspace = guarded(live = false) {
        check(!candidate.consumed && candidate.revision == catalogRevision(config)) { "Capability catalog changed; regenerate" }
        val restored=if(config.retainState)storage.state(id,candidate.source)else null
        CandidateChecks(context,config,sandbox(),candidate.log.bind(id,session)).use{it.prepare(candidate.source,candidate.output,restored)}
        // The preparation isolate is gone. Activation initializes independently.
        PreparedWorkspace(UUID.randomUUID().toString(),id,title,session,candidate,candidate.log.bind(id,session).run).also{prepared[it.token]=it}
    }
    internal fun persistPrepared(token:String) {
        val store=prepared[token] ?: throw ProtocolFailure("INVALID_HANDLE","Unknown prepared store")
        val candidate=store.candidate
        storage.saveWorkspace(store.id,store.title,candidate.source,candidate.origin,candidate.attempts,config.target)
        store.persisted=true
    }
    internal fun activatePrepared(token:String):Pair<LiveWorkspace,JSONObject> {
        val store=prepared[token] ?: throw ProtocolFailure("INVALID_HANDLE","Unknown prepared store")
        check(store.persisted){"Prepared store has not been persisted"}
        check(!workspaces.containsKey(store.id)){"Workspace already active"}
        val candidate=store.candidate
        val live=LiveWorkspace(store.id,store.title,UiSession(context,capabilities,sandbox(),config,candidate.log.bind(store.id,store.session),candidate.identity,store.id),candidate.output,candidate.source,candidate.origin,candidate.attempts)
        val snapshot=try {
            val restored=if(config.retainState)storage.state(store.id,candidate.source)else null
            live.session.mount(candidate.output,restored)
        }catch(error:Throwable){runCatching{live.session.close()}.onFailure(error::addSuppressed);throw error}
        candidate.consumed=true
        workspaces[store.id]=live
        prepared.remove(token)
        return live to snapshot
    }
    internal fun abortPrepared(token:String) {
        prepared.remove(token)
    }
    /** Native host acknowledges publication separately from runtime snapshot production. */
    internal fun frameVisibility(id:String,visible:Boolean){workspaces[id]?.session?.frameVisibility(visible)}
    fun published(id: String, version: Int) {
        val live = workspaces[id] ?: return
        live.session.published(version)
    }
    fun workspace(id: String): LiveWorkspace? = workspaces[id]
    private fun checkpoint(live: LiveWorkspace) {
        if (config.retainState) live.session.persistedState()?.let { state ->
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
        prepared.keys.toList().forEach { try{abortPrepared(it)}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)} }
        workspaces.values.toList().forEach { try{closeWorkspace(it.id)}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)} }
        val active=engine;engine=null
        try{active?.let{SandboxEngine.release(it)}}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}
        failure?.let{throw it}
    }
}
