package dev.deal.embedding.capabilities

import dev.deal.embedding.*
import android.content.Context
import android.os.*
import org.json.*
import java.util.UUID
import java.util.concurrent.Executors

internal interface CapabilitySession : AutoCloseable {
    val identity: String
    fun receive(request: JSONObject, now: Long)
    fun drain(now: Long): List<JSONObject>
    /** The live Session facts compiled admission re-reads before a send and before a reply: the
     * Session lifetime, the permission epoch of each module, the granted modules, the host
     * classification of each disclosed function, the host confirmation of one target and validated
     * argument snapshot, the Experience origin, the frozen-request determination, clock trust and the
     * configured window. Facts, never decisions: the generated Experience decides with them, and the
     * host dispatch boundary decides every call with the same facts. */
    fun authority(): String
    /** A Session suspend or resume starts a new clock period: every relative window captured under an
     * earlier period becomes undecidable, so compiled admission refuses its reply. */
    fun periodChanged()
}

internal interface CapabilityHost {
    fun openSession(log: dev.deal.embedding.EmbeddingLog, origin: String = "supplied"): CapabilitySession
}

/** Discovery and grants are shared; each isolate owns an independent request/reply session. */
internal class CapabilityRegistry(private val context: Context, private val discovery: CapabilityDiscovery, local: List<NativeCapabilities>, private val limits: ExecutionLimits) : CapabilityHost, AutoCloseable {
    private val log = dev.deal.embedding.EmbeddingLog(context)
    private data class Provider(val contract: CapabilityContract, val local: NativeCapabilities? = null, val endpoint: CapabilityDiscovery.Endpoint? = null) {
        /** The disclosed form: a local registration's per-function class travels with its contract, and a
         * discovered contract already carries the class its provider published. */
        val disclosed: CapabilityContract get() = local?.let { CapabilityContract.parse(it.declaration()) } ?: contract
    }
    private val locals = local.toList()
    /** One atomic disclosure: a rejected proposal keeps these providers, their bindings and grants. */
    private val disclosure = CapabilityDisclosure(locals.map { Provider(it.contract, it) }) { it.contract }
    private val providers: Map<String, Provider> get() = disclosure.accepted()
    private var permission: ((String) -> String?)? = null
    /** Trusted controller-local permission is authoritative; providers still enforce consent. */
    @Synchronized internal fun permissions(check: (String) -> String?) { check(!closed && sessions.isEmpty()); permission = check }
    private fun epoch(module: String): String? = permission?.invoke(module)
    private fun allowed(module: String): Boolean = epoch(module)!=null
    private val io = Executors.newSingleThreadExecutor()
    /** The host's own decision about one mutation, bound to its target and validated argument snapshot. */
    private data class Confirmation(val module: String, val function: String, val arguments: String, val status: String, val epoch: String?) {
        /** An approval made under another grant epoch no longer correlates with the live permission. */
        fun status(live: String?): String = if (status == "approved" && live != epoch) "stale" else status
    }
    /** Host confirmations keyed by `module/function#argumentsJson`, the same shape the facts publish. */
    private val confirmations = mutableMapOf<String, Confirmation>()
    private val epochNumbers = mutableMapOf<String, Int>()
    private var nextEpochNumber = 0
    private data class Pending(val id: Int, val key: String, val provider: Provider, val function: CapabilityFunction, val deadline: Long, val permission: String, val bytes:Int)
    private val sessions = mutableSetOf<Session>()
    private var closed = false
    fun refresh(): List<CapabilityContract> {
        val discovered = discovery.discover()
        val proposal = locals.map { Provider(it.contract, it) } + discovered.map { Provider(it.contract, endpoint = it) }
        return synchronized(this) {
            check(!closed) { "Capability registry is closed" }
            // The whole proposal is inside the boundary before any contract, binding or grant changes.
            disclosure.admit(proposal, sessions.isNotEmpty())
            disclosed()
        }
    }
    /** The accepted contracts as discovery publishes them, each function carrying its host class. */
    private fun disclosed(): List<CapabilityContract> = providers.values.map { it.disclosed }
    @Synchronized fun contracts(): List<CapabilityContract> = disclosed()
    /**
     * The host's confirmation of one mutation: an approval covers exactly this target and validated
     * argument snapshot, a denial is final for that call, and an approval taken under a grant epoch
     * that has since changed is stale. Nothing here invokes a provider or grants a module.
     */
    @Synchronized fun confirm(module: String, function: String, argumentsJson: String, status: String) {
        check(!closed) { "Capability registry is closed" }
        require(status in setOf("approved", "denied", "stale")) { "Unknown confirmation status: $status" }
        val provider = providers[module] ?: throw ProtocolFailure("INVALID_ARGUMENT", "Confirmation names no disclosed module '$module'")
        val target = provider.contract.functions.singleOrNull { it.name == function } ?: throw ProtocolFailure("INVALID_ARGUMENT", "Confirmation names no disclosed function '$module.$function'")
        val arguments = JSONArray(argumentsJson)
        target.validateArguments(arguments)
        confirmations[module + "/" + function + "#" + arguments.toString()] = Confirmation(module, function, arguments.toString(), status, epoch(module))
    }
    /** The facts of one Session: classification of every accepted function, live confirmation statuses,
     * the Experience origin and the frozen-request determination. Generation issues no determination,
     * so the published flag is false and a mutation needs the host confirmation. */
    private fun facts(origin: String): CapabilityFacts {
        val classes = acceptedClasses(providers.values.map { it.contract to (it.local?.classification() ?: emptyMap()) })
        val confirmation = mutableMapOf<String, String>()
        for ((key, approval) in confirmations) { confirmation[key] = approval.status(epoch(approval.module)) }
        return CapabilityFacts(classes, confirmation, origin, false, "")
    }
    @Synchronized override fun openSession(log: dev.deal.embedding.EmbeddingLog, origin: String): CapabilitySession {
        check(!closed) { "Capability registry is closed" }
        return Session(log, origin).also { sessions.add(it) }
    }
    /** One explicit trusted read through the same grant/provider/schema boundary as UI calls.
     * The caller selects disclosed data; this method adds no model or write authority. */
    fun read(module: String, function: String, arguments: JSONArray, log: dev.deal.embedding.EmbeddingLog): Any {
        openSession(log).use { session ->
            session.receive(JSONObject().put("id", 1).put("module", module).put("function", function).put("args", arguments), android.os.SystemClock.elapsedRealtime())
            while (true) {
                val reply = session.drain(android.os.SystemClock.elapsedRealtime()).firstOrNull()
                if (reply != null) {
                    if (!reply.getBoolean("ok")) { val e = reply.getJSONObject("error"); throw dev.deal.embedding.ProtocolFailure(e.getString("code"), e.getString("message")) }
                    return reply.get("value")
                }
                Thread.sleep(10)
            }
        }
    }
    private fun cancelRemote(item: Pending) { io.execute { try { item.provider.endpoint?.remote?.cancel(item.key); log.event("transport", "cancelled", operation = item.key, target = item.provider.contract.module) } catch (_: Exception) { log.event("transport", "failed", operation = item.key, code = "CANCEL_FAILED") } } }

    /** Session operations and callbacks serialize; permission queries read trusted isolate epochs. */
    private inner class Session(private val log: dev.deal.embedding.EmbeddingLog, private val origin: String) : CapabilitySession {
        // The Session clock period. A suspend and a resume each advance it, so a relative expiry an
        // invocation captured under the previous period is never re-trusted by a later matched reading.
        private var trustPeriod = 0L
        override fun periodChanged() { synchronized(this@CapabilityRegistry) { trustPeriod++ } }
        override val identity = UUID.randomUUID().toString()
        private val calls = CapabilityCall { facts(origin) }
        private val pending = mutableMapOf<Int, Pending>()
        private val replies = mutableListOf<JSONObject>()
        private var nextId = 1L
        private val maximumBufferedBytes=128*1024
        private fun bufferedBytes()=replies.sumOf{it.toString().toByteArray().size}+pending.values.sumOf{it.bytes}
        private var ended = false
        private fun live() { check(!closed && !ended) { "Capability session is closed" } }
        /**
         * One terminal failure of this lane. The reply carries the failure's own code, its bounded message and
         * the outcome the transport could decide, so the Session receives a typed failure instead of a
         * substitute code or a substitute reason; the code names the operation only for observation.
         */
        private fun fail(id: Int, failure: ProviderFailure) {
            log.event("transport", if (failure.outcome == ProviderFailure.UNKNOWN) "unknown" else "failed", operation = "$identity:$id", code = failure.code, span = "$identity:$id")
            pending.remove(id)
            replies.add(JSONObject().put("id", id).put("ok", false).put("error", failure.json()))
        }
        /** A refusal or a provider's own answer: the call changed nothing that could still happen. */
        private fun fail(id: Int, code: String, message: String) { fail(id, ProviderFailure.answered(code, message, limits.failureBounds())) }
        /**
         * A failure of a call that had already left the lane: from the instant it became pending the provider
         * may have received it, so an external mutation's outcome is not decidable here. A read mutated nothing
         * outside, so only its own result failed.
         */
        private fun lost(id: Int, cause: String, message: String, function: CapabilityFunction, provider: Provider) {
            fail(id, ProviderFailure.lost(cause, message, hostClass(function, provider.local?.classification() ?: emptyMap()) != "read", limits.failureBounds()))
        }
        private fun success(id: Int, value: Any) { log.event("transport", "completed", operation = "$identity:$id", span = "$identity:$id"); pending.remove(id); replies.add(JSONObject().put("id", id).put("ok", true).put("value", value)) }
        override fun receive(request: JSONObject, now: Long) {
            // Reconnect only on a new explicit request, never replay a failed operation.
            // Discovery runs off the registry lock; refresh enforces unchanged active contracts.
            val reconnect = synchronized(this@CapabilityRegistry) {
                live()
                providers[request.optString("module")]?.endpoint?.remote?.asBinder()?.isBinderAlive == false
            }
            if (reconnect) {
                try { refresh() }
                catch (error: Exception) { log.event("discovery", "failed", operation = "$identity:${request.opt("id")}", code = "RECONNECT_FAILED") }
            }
            synchronized(this@CapabilityRegistry) {
            live()
            val id = request.opt("id")
            require(id is Int && id.toLong() == nextId) { "Invalid capability request id: expected $nextId" }
            check(pending.size+replies.size<8 && bufferedBytes()<=maximumBufferedBytes-16*1024){"Capability buffer full; drain replies before admitting more work"}
            nextId++
            if(request.toString().toByteArray().size>16*1024){fail(id,"LIMIT","Capability request too large");return@synchronized}
            log.event("transport", "received", operation = "$identity:$id", target = request.optString("module") + "." + request.optString("function"), detail = request.toString(), span = "$identity:$id")
            val provider = providers[request.optString("module")]
            val function = provider?.contract?.functions?.singleOrNull { it.name == request.optString("function") }
            val args = request.optJSONArray("args")
            if (request.length() != 4 || provider == null || function == null || args == null) { fail(id, "BAD_REQUEST", "Unknown typed capability"); return@synchronized }
            try { function.validateArguments(args) } catch (_: Exception) { fail(id, "BAD_REQUEST", "Invalid capability arguments"); return@synchronized }
            // A module grant never authorizes a mutation: the one send admission decides the call with the
            // host's classification, its confirmation of this exact target and validated argument snapshot,
            // the frozen-request determination and the live grant, before it becomes pending or reaches a
            // local or remote provider. A refused call dispatches nothing and publishes only its refusal.
            val refusal = calls.admit(provider.contract.module, function.name, args.toString(), allowed(provider.contract.module))
            if (refusal != null) { fail(id, refusal.code, refusal.reason); return@synchronized }
            if (pending.size >= 8) { fail(id, "LIMIT", "Too many pending requests"); return@synchronized }
            if (provider.local != null) {
                val value = try { calls.invoke(provider.local,function,args,InvocationContext(log.experienceIdentity)) }
                    catch (error: Exception) { fail(id, ProviderFailure.answered(error, limits.failureBounds())); return@synchronized }
                // The provider answered, so its own code and message stand; a reply this boundary cannot publish
                // is a failure of the call it already ran, not of a call that never happened.
                try { calls.check(provider.local,function,value) }
                    catch (_: Exception) { lost(id, "BAD_RESPONSE", "Host response failed validation", function, provider); return@synchronized }
                if (JSONArray().put(value).toString().toByteArray().size > 16*1024) { lost(id, "BAD_RESPONSE", "Host response too large", function, provider); return@synchronized }
                success(id,value)
                return@synchronized
            }
            val endpoint = provider.endpoint!!
            val item = Pending(id, "$identity:$id", provider, function, limits.providerDeadline(now), epoch(provider.contract.module) ?: run{fail(id,"REVOKED","Capability access revoked");return@synchronized},16*1024)
            pending[id] = item
            val callback = object : ICapabilityResult.Stub() {
                private fun authenticate() { require(Binder.getCallingUid() == endpoint.uid && context.packageManager.getPackagesForUid(endpoint.uid)?.contains(endpoint.component.packageName) == true) { "Unauthenticated provider callback" } }
                override fun success(key: String, valueJson: String) = synchronized(this@CapabilityRegistry) {
                    authenticate()
                    if (pending[id] !== item || key != item.key || ended || closed) { log.event("transport", "discarded", operation = item.key, code = "STALE_CALLBACK"); return@synchronized }
                    if (epoch(provider.contract.module)!=item.permission) { lost(id, "REVOKED", "Capability access revoked", function, provider); return@synchronized }
                    try {
                        require(valueJson.toByteArray().size <= 16 * 1024)
                        val values = JSONArray(valueJson); require(values.length() == 1)
                        val value = values.get(0); function.result.validate(value)
                        success(id, value)
                    } catch (_: Exception) { lost(id, "BAD_RESPONSE", "Provider response failed validation", function, provider) }
                }
                override fun failure(key: String, code: String, message: String) = synchronized(this@CapabilityRegistry) {
                    authenticate()
                    // One call admits one terminal reply: a callback that answers another call, a call this lane
                    // already answered, or the work of a closed Session publishes nothing and retires nothing.
                    if (pending[id] !== item || key != item.key || ended || closed) { log.event("transport", "discarded", operation = item.key, code = "STALE_CALLBACK"); return@synchronized }
                    // The provider answered its own call, so its typed code and message are the failure this
                    // lane publishes: no allow-list rewrites a custom code into a generic one.
                    fail(id, ProviderFailure.answered(code, message, limits.failureBounds()))
                }
            }
            io.execute {
                try {
                    val admitted = synchronized(this@CapabilityRegistry) { pending[id] === item && epoch(provider.contract.module)==item.permission }
                    if (!admitted) { synchronized(this@CapabilityRegistry) { if (pending[id] === item) fail(id, "REVOKED", "Capability access revoked") }; return@execute }
                    log.event("transport", "sent", operation = item.key, target = provider.contract.module + "." + function.name, span = item.key)
                    endpoint.remote.invoke(item.key, function.name, args.toString(), callback)
                    synchronized(this@CapabilityRegistry) { if (pending[id] !== item) endpoint.remote.cancel(item.key) }
                } catch (_: Exception) { synchronized(this@CapabilityRegistry) { if (pending[id] === item) lost(id, "PROVIDER_DIED", "Provider unavailable", function, provider) } }
            }
            }
        }
        override fun drain(now: Long): List<JSONObject> = synchronized(this@CapabilityRegistry) {
            live()
            pending.values.filter { epoch(it.provider.contract.module)!=it.permission || it.deadline <= now || it.provider.endpoint?.remote?.asBinder()?.isBinderAlive == false }.toList().forEach {
                cancelRemote(it); val cause: String = if (epoch(it.provider.contract.module)!=it.permission) "REVOKED" else if (it.deadline <= now) "TIMEOUT" else "PROVIDER_DIED"
                val message: String = if (cause == "REVOKED") "Capability access revoked" else if (cause == "TIMEOUT") "Capability call timed out" else "Capability unavailable"
                lost(it.id, cause, message, it.function, it.provider)
            }
            replies.toList().also { replies.clear() }
        }
        /** The live facts of this Session, read by compiled admission. Grant tokens stay opaque here;
         * a stable integer identity per token is what the generated Experience captures and compares.
         * An unknown module, an unclassified function or a closed Session admits nothing. Every
         * disclosed function carries the host-configured window of one provider call, measured from the
         * admission that issues it to its terminal reply: the emitted reply admission expires no later
         * than the instant the lane fails the same call at, and neither bound is an adapter literal. */
        override fun authority(): String = synchronized(this@CapabilityRegistry) {
            val granted = JSONArray()
            val epochs = JSONObject()
            val expiry = JSONObject()
            val window = limits.providerTimeoutMs
            for (provider in providers.values) {
                val token = epoch(provider.contract.module)
                if (token != null) granted.put(provider.contract.module)
                epochs.put(provider.contract.module, if (token == null) -1 else (epochNumbers.getOrPut(token) { nextEpochNumber++ }))
                for (function in provider.contract.functions) {
                    val key = provider.contract.module + "/" + function.name
                    expiry.put(key, JSONObject().put("relativeMs", window).put("absolute", -1))
                }
            }
            // The classification, the live confirmation statuses, the origin and the determination are
            // the one fact set the host dispatch boundary also decides with; they are never derived a
            // second way. An approval under another grant epoch is published as stale so no consumer
            // reads a confirmation that no longer correlates with the live permission.
            val facts = facts(origin)
            val classification = JSONObject()
            for ((key, value) in facts.classification) { classification.put(key, value) }
            val confirmation = JSONObject()
            for ((key, value) in facts.confirmation) { confirmation.put(key, value) }
            JSONObject().put("lifetime", if (closed || ended) "" else identity).put("epochs", epochs).put("granted", granted)
                .put("classification", classification).put("confirmation", confirmation).put("clockTrusted", !(closed || ended))
                .put("period", trustPeriod)
                .put("origin", facts.origin).put("requestAuthorizes", facts.requestAuthorizes).put("requestTarget", facts.requestTarget)
                .put("now", android.os.SystemClock.elapsedRealtime()).put("expiry", expiry).toString()
        }
        override fun close(): Unit = synchronized(this@CapabilityRegistry) {
            if (ended) return@synchronized
            ended = true
            pending.values.toList().forEach(::cancelRemote)
            pending.clear(); replies.clear(); sessions.remove(this)
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        sessions.toList().forEach { it.close() }
        closed = true; discovery.close(); io.shutdown()
    }
}
