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
}

internal interface CapabilityHost {
    fun openSession(log: dev.deal.embedding.EmbeddingLog): CapabilitySession
}

/** Discovery and grants are shared; each isolate owns an independent request/reply session. */
internal class CapabilityRegistry(private val context: Context, private val discovery: CapabilityDiscovery, local: List<NativeCapabilities>) : CapabilityHost, AutoCloseable {
    private val log = dev.deal.embedding.EmbeddingLog(context)
    private data class Provider(val contract: CapabilityContract, val local: NativeCapabilities? = null, val endpoint: CapabilityDiscovery.Endpoint? = null)
    private val locals = local.associateBy { it.contract.module }
    private var providers = locals.mapValues { Provider(it.value.contract, it.value) }
    private var permission: ((String) -> String?)? = null
    /** Trusted controller-local permission is authoritative; providers still enforce consent. */
    @Synchronized internal fun permissions(check: (String) -> String?) { check(!closed && sessions.isEmpty()); permission = check }
    private fun epoch(module: String): String? = permission?.invoke(module)
    private fun allowed(module: String): Boolean = epoch(module)!=null
    private val io = Executors.newSingleThreadExecutor()
    private data class Pending(val id: Int, val key: String, val provider: Provider, val deadline: Long, val permission: String, val bytes:Int)
    private val sessions = mutableSetOf<Session>()
    private var closed = false
    init { require(locals.size == local.size) }
    fun refresh(): List<CapabilityContract> {
        val discovered = discovery.discover()
        val next = locals.mapValues { Provider(it.value.contract, it.value) }.toMutableMap()
        for (endpoint in discovered) {
            require(!next.containsKey(endpoint.contract.module)) { "Ambiguous capability module: ${endpoint.contract.module}" }
            next[endpoint.contract.module] = Provider(endpoint.contract, endpoint = endpoint)
        }
        return synchronized(this) {
            check(!closed) { "Capability registry is closed" }
            if (sessions.isNotEmpty()) require(providers.all { (module, p) -> next[module]?.contract?.json()?.toString() == p.contract.json().toString() }) { "Close active sessions before changing existing capability contracts" }
            providers = next; contracts()
        }
    }
    @Synchronized fun contracts(): List<CapabilityContract> = providers.values.map { it.contract }
    @Synchronized override fun openSession(log: dev.deal.embedding.EmbeddingLog): CapabilitySession {
        check(!closed) { "Capability registry is closed" }
        return Session(log).also { sessions.add(it) }
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
    private inner class Session(private val log: dev.deal.embedding.EmbeddingLog) : CapabilitySession {
        override val identity = UUID.randomUUID().toString()
        private val pending = mutableMapOf<Int, Pending>()
        private val replies = mutableListOf<JSONObject>()
        private var nextId = 1L
        private val maximumBufferedBytes=128*1024
        private fun bufferedBytes()=replies.sumOf{it.toString().toByteArray().size}+pending.values.sumOf{it.bytes}
        private var ended = false
        private fun live() { check(!closed && !ended) { "Capability session is closed" } }
        private fun fail(id: Int, code: String, message: String) {
            log.event("transport", "failed", operation = "$identity:$id", code = code, span = "$identity:$id")
            pending.remove(id)
            replies.add(JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", code).put("message", message.take(200))))
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
            if (!allowed(provider.contract.module)) { fail(id, "DENIED", "Capability access not granted"); return@synchronized }
            if (pending.size >= 8) { fail(id, "LIMIT", "Too many pending requests"); return@synchronized }
            if (provider.local != null) {
                try {
                    val value=provider.local.invoke(function.name,args,InvocationContext(log.experienceIdentity))
                    function.result.validate(value)
                    require(JSONArray().put(value).toString().toByteArray().size<=16*1024){"Host response too large"}
                    success(id,value)
                }
                catch (error: Exception) { fail(id, "HOST_ERROR", error.message ?: "Host operation failed") }
                return@synchronized
            }
            val endpoint = provider.endpoint!!
            val item = Pending(id, "$identity:$id", provider, now + 5000, epoch(provider.contract.module) ?: run{fail(id,"REVOKED","Capability access revoked");return@synchronized},16*1024)
            pending[id] = item
            val callback = object : ICapabilityResult.Stub() {
                private fun authenticate() { require(Binder.getCallingUid() == endpoint.uid && context.packageManager.getPackagesForUid(endpoint.uid)?.contains(endpoint.component.packageName) == true) { "Unauthenticated provider callback" } }
                override fun success(key: String, valueJson: String) = synchronized(this@CapabilityRegistry) {
                    authenticate()
                    if (pending[id] !== item || key != item.key || ended || closed) { log.event("transport", "discarded", operation = item.key, code = "STALE_CALLBACK"); return@synchronized }
                    if (epoch(provider.contract.module)!=item.permission) { fail(id, "REVOKED", "Capability access revoked"); return@synchronized }
                    try {
                        require(valueJson.toByteArray().size <= 16 * 1024)
                        val values = JSONArray(valueJson); require(values.length() == 1)
                        val value = values.get(0); function.result.validate(value)
                        success(id, value)
                    } catch (_: Exception) { fail(id, "BAD_RESPONSE", "Provider response failed validation") }
                }
                override fun failure(key: String, code: String, message: String) = synchronized(this@CapabilityRegistry) {
                    authenticate()
                    if (pending[id] === item && key == item.key && !ended && !closed) {
                        val safe = if (code in setOf("PROVIDER_DENIED", "PROVIDER_REVOKED", "BAD_REQUEST")) code else "PROVIDER_ERROR"
                        fail(id, safe, message)
                    }
                }
            }
            io.execute {
                try {
                    val admitted = synchronized(this@CapabilityRegistry) { pending[id] === item && epoch(provider.contract.module)==item.permission }
                    if (!admitted) { synchronized(this@CapabilityRegistry) { if (pending[id] === item) fail(id, "REVOKED", "Capability access revoked") }; return@execute }
                    log.event("transport", "sent", operation = item.key, target = provider.contract.module + "." + function.name, span = item.key)
                    endpoint.remote.invoke(item.key, function.name, args.toString(), callback)
                    synchronized(this@CapabilityRegistry) { if (pending[id] !== item) endpoint.remote.cancel(item.key) }
                } catch (_: Exception) { synchronized(this@CapabilityRegistry) { if (pending[id] === item) fail(id, "PROVIDER_DIED", "Provider unavailable") } }
            }
            }
        }
        override fun drain(now: Long): List<JSONObject> = synchronized(this@CapabilityRegistry) {
            live()
            pending.values.filter { epoch(it.provider.contract.module)!=it.permission || it.deadline <= now || it.provider.endpoint?.remote?.asBinder()?.isBinderAlive == false }.toList().forEach {
                cancelRemote(it); fail(it.id, if (epoch(it.provider.contract.module)!=it.permission) "REVOKED" else if (it.deadline <= now) "TIMEOUT" else "PROVIDER_DIED", "Capability unavailable")
            }
            replies.toList().also { replies.clear() }
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
