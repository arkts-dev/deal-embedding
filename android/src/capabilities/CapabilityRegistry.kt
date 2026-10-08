package dev.deal.embedding.capabilities

import android.content.Context
import android.os.*
import org.json.*
import java.util.UUID
import java.util.concurrent.Executors

interface CapabilitySession : AutoCloseable {
    fun receive(request: JSONObject, now: Long)
    fun drain(now: Long): List<JSONObject>
}

interface CapabilityHost {
    fun openSession(): CapabilitySession
}

/** Discovery and grants are shared; each isolate owns an independent request/reply session. */
class CapabilityRegistry(private val context: Context, private val discovery: CapabilityDiscovery, local: List<NativeCapabilities>) : CapabilityHost, AutoCloseable {
    private data class Provider(val contract: CapabilityContract, val local: NativeCapabilities? = null, val endpoint: CapabilityDiscovery.Endpoint? = null)
    private val locals = local.associateBy { it.contract.module }
    private var providers = locals.mapValues { Provider(it.value.contract, it.value) }
    private val grants = mutableSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private data class Pending(val id: Int, val key: String, val provider: Provider, val deadline: Long)
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
    @Synchronized fun grant(module: String) { check(!closed) { "Capability registry is closed" }; require(providers.containsKey(module)); grants.add(module) }
    @Synchronized fun revoke(module: String) {
        grants.remove(module)
        sessions.forEach { it.revoke(module) }
    }
    @Synchronized fun grantAll() { providers.keys.forEach(::grant) }
    @Synchronized fun revokeAll() { grants.toList().forEach(::revoke) }
    @Synchronized override fun openSession(): CapabilitySession {
        check(!closed) { "Capability registry is closed" }
        return Session().also { sessions.add(it) }
    }
    private fun cancelRemote(item: Pending) { io.execute { try { item.provider.endpoint?.remote?.cancel(item.key) } catch (_: Exception) {} } }

    /** Every session operation and callback uses the registry lock, including grant changes. */
    private inner class Session : CapabilitySession {
        private val identity = UUID.randomUUID().toString()
        private val pending = mutableMapOf<Int, Pending>()
        private val replies = mutableListOf<JSONObject>()
        private var nextId = 1
        private var ended = false
        private fun live() { check(!closed && !ended) { "Capability session is closed" } }
        private fun fail(id: Int, code: String, message: String) {
            pending.remove(id)
            replies.add(JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", code).put("message", message.take(200))))
        }
        private fun success(id: Int, value: Any) { pending.remove(id); replies.add(JSONObject().put("id", id).put("ok", true).put("value", value)) }
        fun revoke(module: String) {
            pending.values.filter { it.provider.contract.module == module }.toList().forEach { cancelRemote(it); fail(it.id, "REVOKED", "Capability access revoked") }
        }
        override fun receive(request: JSONObject, now: Long): Unit = synchronized(this@CapabilityRegistry) {
            live()
            val id = request.opt("id")
            require(id is Int && id == nextId && nextId <= 128) { "Invalid capability request id: expected $nextId (session limit 128)" }
            nextId++
            val provider = providers[request.optString("module")]
            val function = provider?.contract?.functions?.singleOrNull { it.name == request.optString("function") }
            val args = request.optJSONArray("args")
            if (request.length() != 4 || provider == null || function == null || args == null) { fail(id, "BAD_REQUEST", "Unknown typed capability"); return@synchronized }
            try { function.validateArguments(args) } catch (_: Exception) { fail(id, "BAD_REQUEST", "Invalid capability arguments"); return@synchronized }
            if (provider.contract.module !in grants) { fail(id, "DENIED", "Capability access not granted"); return@synchronized }
            if (pending.size >= 8) { fail(id, "LIMIT", "Too many pending requests"); return@synchronized }
            if (provider.local != null) {
                try { success(id, provider.local.invoke(function.name, args)) }
                catch (error: Exception) { fail(id, "HOST_ERROR", error.message ?: "Host operation failed") }
                return@synchronized
            }
            val endpoint = provider.endpoint!!
            val item = Pending(id, "$identity:$id", provider, now + 5000)
            pending[id] = item
            val callback = object : ICapabilityResult.Stub() {
                private fun authenticate() { require(Binder.getCallingUid() == endpoint.uid && context.packageManager.getPackagesForUid(endpoint.uid)?.contains(endpoint.component.packageName) == true) { "Unauthenticated provider callback" } }
                override fun success(key: String, valueJson: String) = synchronized(this@CapabilityRegistry) {
                    authenticate()
                    if (pending[id] !== item || key != item.key || ended || closed) return@synchronized
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
                    endpoint.remote.invoke(item.key, function.name, args.toString(), callback)
                    synchronized(this@CapabilityRegistry) { if (pending[id] !== item) endpoint.remote.cancel(item.key) }
                } catch (_: Exception) { synchronized(this@CapabilityRegistry) { if (pending[id] === item) fail(id, "PROVIDER_DIED", "Provider unavailable") } }
            }
        }
        override fun drain(now: Long): List<JSONObject> = synchronized(this@CapabilityRegistry) {
            live()
            pending.values.filter { it.deadline <= now || it.provider.endpoint?.remote?.asBinder()?.isBinderAlive == false }.toList().forEach {
                cancelRemote(it); fail(it.id, if (it.deadline <= now) "TIMEOUT" else "PROVIDER_DIED", "Capability unavailable")
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
