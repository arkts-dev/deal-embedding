package dev.deal.embedding.discovery

import android.content.Context
import dev.deal.embedding.*
import dev.deal.embedding.capabilities.CapabilityBoundary
import org.json.*
import java.security.MessageDigest
import java.util.concurrent.*

/** Already authorized, host-supplied evidence. Descriptions are data, not model instructions. */
data class DiscoveryFact(val id: String, val text: String)

/** Frozen authorized evidence. The bound protocol supplies the catalogue, never the caller. */
internal class DiscoveryFacts(facts: List<DiscoveryFact>) {
    internal val factsJson: String
    internal val supportingFactIds: Set<String>
    val identity: String
    init {
        if (facts.size !in 1..16) throw ProtocolFailure("INVALID", "Facts: 1-16 unique fact identifiers")
        if (facts.map { it.id }.distinct().size != facts.size) throw ProtocolFailure("INVALID", "Facts: 1-16 unique fact identifiers")
        facts.forEach { fact ->
            if (!fact.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) throw ProtocolFailure("INVALID", "Fact identifier: [A-Za-z][A-Za-z0-9_-]{0,63}")
            if (fact.text.isBlank()) throw ProtocolFailure("INVALID", "Fact text: nonblank")
            if (fact.text.length > 2048) throw ProtocolFailure("INVALID", "Fact text: at most 2048 UTF-16 units")
        }
        factsJson = JSONArray(facts.sortedBy { it.id }.map { JSONObject().put("id", it.id).put("text", it.text) }).toString()
        supportingFactIds = facts.map { it.id }.toSet()
        // Discovery input bound: inadmissible evidence is rejected before any inference is paid for.
        if (contextJson().toByteArray(Charsets.UTF_8).size > 8192) throw ProtocolFailure("INVALID", "Facts context: at most 8192 UTF-8 bytes")
        identity = digest(factsJson)
    }
    internal fun contextJson() = JSONObject().put("facts", JSONArray(factsJson)).toString()
    internal companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Internal exact-input capture; canonical contract fields are serialized without additions. */
internal class CapturedDiscoveryFacts(val evidence: DiscoveryFacts, contracts: List<CapabilityContract>) {
    val factsJson get() = evidence.factsJson
    val supportingFactIds get() = evidence.supportingFactIds
    val catalogueJson: String
    val capabilityIds: Set<String>
    init {
        if (contracts.size > 32) throw ProtocolFailure("INVALID", "Catalogue: at most 32 modules, got ${contracts.size}")
        // One contract validator owns every per-contract bound and module uniqueness. Admission
        // reports any rejected catalogue as the discovery handoff's located INVALID.
        try { CapabilityBoundary.validate(contracts) } catch (error: ProtocolFailure) {
            if (error.code == "INVALID_ARGUMENT") throw ProtocolFailure("INVALID", "Catalogue: " + (error.message ?: "rejected")) else throw error
        }
        catalogueJson = JSONArray(contracts.sortedBy { it.module }.map { c ->
            c.json().put("functions", JSONArray(c.functions.sortedBy { it.name }.map { it.json() }))
        }).toString()
        val bytes = catalogueJson.toByteArray(Charsets.UTF_8).size
        if (bytes > 32768) throw ProtocolFailure("INVALID", "Catalogue: at most 32768 UTF-8 bytes, got $bytes")
        capabilityIds = contracts.flatMap { c -> c.functions.map { "${c.module}.${it.name}" } }.toSet()
    }
}

/** Issued by one discovery owner; text and evidence stay frozen through generation admission. */
class DiscoverySuggestion internal constructor(
    val title: String, val brief: String, supportingFactIds: List<String>, capabilityIds: List<String>,
    internal val input: CapturedDiscoveryFacts,
) {
    private val referencedFacts = supportingFactIds.toList()
    private val referencedCapabilities = capabilityIds.toList()
    val supportingFactIds: List<String> get() = referencedFacts.toList()
    val capabilityIds: List<String> get() = referencedCapabilities.toList()
}

/** Foreground opportunity recognition only. Calls execute on an owned serial lane, never providers. */
class Discovery private constructor(private val api: ApplicationProtocol, private val inference: DiscoveryInference, private val context: Context, private val catalogue: () -> List<CapabilityContract>) : AutoCloseable {
    /** Null selects local inference. Remote selection is explicit native bootstrap configuration. */
    internal constructor(context: Context, api: ApplicationProtocol, gateway: GenerationGatewayConfig? = null) : this(api,
        if (gateway == null) NanoDiscovery(context.applicationContext) else GatewayDiscovery(gateway), context.applicationContext, { api.refresh() })
    private val policy = DiscoveryPolicy(context)
    private val worker = Executors.newSingleThreadExecutor()
    private var revision = 0L
    private var closed = false
    private var pending: CompletableFuture<DiscoverySuggestion?>? = null
    private var issued: DiscoverySuggestion? = null
    private var issuedLog: EmbeddingLog? = null
    internal constructor(context: Context, api: ApplicationProtocol, catalogue: () -> List<CapabilityContract> = { api.refresh() }, infer: (String) -> String) : this(api, object : DiscoveryInference {
        override fun complete(prompt: String, log: EmbeddingLog) = infer(prompt)
        override fun cancel() {}
        override fun close() {}
    }, context.applicationContext, catalogue)
    fun discover(facts: List<DiscoveryFact>): CompletableFuture<DiscoverySuggestion?> = discover(DiscoveryFacts(facts))
    @Synchronized internal fun discover(input: DiscoveryFacts): CompletableFuture<DiscoverySuggestion?> {
        check(!closed)
        cancel()
        val epoch = revision
        val result = CompletableFuture<DiscoverySuggestion?>()
        pending = result
        val log = EmbeddingLog(context)
        val started = android.os.SystemClock.elapsedRealtime()
        log.event("opportunity", "started", target = inference.target, detail = input.contextJson())
        result.whenComplete { tip, error ->
            // Classification belongs in evidence: an invalid reply must not look like a model outage.
            val code = when {
                error == null -> ""
                error is CancellationException -> "CANCELLED"
                error is ProtocolFailure -> error.code
                error is IllegalArgumentException -> "INVALID_REPLY"
                else -> "INFERENCE_FAILED"
            }
            // Keep the failing rule visible: a rejected reply is a model-format problem, not an outage.
            val reason = error?.message?.take(120) ?: ""
            log.event("opportunity", if (error is CancellationException) "cancelled" else if (error != null) "failed" else if (tip == null) "none" else "suggested",
                target = inference.target, code = code, operation = reason, durationMs = android.os.SystemClock.elapsedRealtime() - started,
                detail = if (error != null) android.util.Log.getStackTraceString(error) else tip?.let { proposalJson(it) })
            if (result.isCancelled) synchronized(this) { if (pending === result) cancel() }
        }
        worker.execute {
            try {
                synchronized(this) { check(!closed && epoch == revision) { "Discovery superseded" } }
                val captured = log.stage("opportunity-catalogue") { CapturedDiscoveryFacts(input, catalogue()) }
                log.event("opportunity-input", "captured", detail = JSONObject(input.contextJson()).put("capabilities", JSONArray(captured.catalogueJson)).toString())
                synchronized(this) { if (closed || epoch != revision) throw CancellationException("Discovery superseded") }
                val reply = log.operation("opportunity-inference", target = inference.target, detail = policy.prompt(captured)) { inferenceLog ->
                    inference.complete(policy.prompt(captured), inferenceLog).also { inferenceLog.event("opportunity-inference", "response", target = inference.target, detail = it) }
                }
                val suggestion = log.stage("opportunity-validation", detail = reply) {
                    try { policy.parse(reply, captured) }
                    catch (error: android.util.MalformedJsonException) { throw IllegalArgumentException("Malformed discovery JSON", error) }
                }
                synchronized(this) {
                    if (closed || epoch != revision) throw CancellationException("Discovery superseded")
                    issued = suggestion; issuedLog = if (suggestion == null) null else log
                    pending = null; result.complete(suggestion)
                }
            } catch (error: Throwable) {
                synchronized(this) { if (pending === result) pending = null }
                result.completeExceptionally(error)
            }
        }
        return result
    }
    /** Host calls this immediately when foreground, facts or permissions change. */
    @Synchronized fun cancel() {
        issuedLog?.event("opportunity", "invalidated")
        revision++; issued = null; issuedLog = null
        val old = pending; pending = null
        inference.cancel(); old?.completeExceptionally(CancellationException("Discovery superseded"))
    }
    /** Canonical revalidation of an issued suggestion against the current facts and refreshed catalogue. */
    internal fun admit(suggestion: DiscoverySuggestion, current: DiscoveryFacts): CapturedDiscoveryFacts {
        val fresh = CapturedDiscoveryFacts(current, catalogue())
        val owned = synchronized(this) { !closed && issued === suggestion }
        policy.admit(suggestion.input, fresh, owned)
        return fresh
    }
    /** Admission uses the exact brief/evidence and verifies the executable catalogue again. */
    fun request(suggestion: DiscoverySuggestion, currentFacts: List<DiscoveryFact>): GenerationRequest = request(suggestion,DiscoveryFacts(currentFacts))
    internal fun request(suggestion: DiscoverySuggestion, current: DiscoveryFacts): GenerationRequest {
        // Captured evidence and ownership are re-admitted by compiled policy, before any request exists.
        val (epoch, log, owned) = synchronized(this) {
            if (closed) throw ProtocolFailure("CANCELLED", "Discovery is closed")
            Triple(revision, issuedLog ?: throw ProtocolFailure("STALE", "Stale or unissued suggestion"), issued === suggestion)
        }
        return log.stage("opportunity-admission", detail = proposalJson(suggestion)) {
        // Catalogue discovery can block: cancellation must remain callable while it runs.
        policy.admit(suggestion.input, CapturedDiscoveryFacts(current, catalogue()), owned)
        // The Request's title, compiled intent and captured-facts context are assembled in compiled
        // policy; this adapter only transports them through the one context validator and issues once.
        val plan = policy.handoff(proposalJson(suggestion), suggestion.input)
        synchronized(this) {
            if (closed || revision != epoch || issued !== suggestion) throw ProtocolFailure("STALE", "Suggestion invalidated during admission")
            // The context mechanism is the one validator of this handoff context; every rejection is located.
            val handoff = try { api.snapshot(plan.schema, plan.context) } catch (error: ProtocolFailure) {
                if (error.code == "INVALID_ARGUMENT") throw ProtocolFailure("INVALID", "Discovery context: " + (error.message ?: "rejected")) else throw error
            }
            api.request(plan.title, plan.intent, handoff).also { request ->
                issued = null; issuedLog = null
                log.event("opportunity-handoff", "admitted", target = runCatching { api.requestTrace(request) }.getOrDefault(""))
            }
        }
        }
    }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true; cancel() }
        teardown({ worker.shutdownNow() }, { inference.close() }, { policy.close() }, { check(worker.awaitTermination(10, TimeUnit.SECONDS)) { "Discovery worker did not terminate" } })
    }
    private fun proposalJson(tip: DiscoverySuggestion) = JSONObject().put("title", tip.title).put("brief", tip.brief).put("supportingFactIds", JSONArray(tip.supportingFactIds)).put("capabilityIds", JSONArray(tip.capabilityIds)).toString()

}

internal interface DiscoveryInference : AutoCloseable {
    val target: String get() = "On-device"
    fun complete(prompt: String, log: EmbeddingLog): String
    fun cancel()
}
