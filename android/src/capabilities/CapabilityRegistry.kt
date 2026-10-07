package dev.deal.embedding.capabilities

import android.content.Context
import android.os.*
import org.json.*
import java.util.UUID
import java.util.concurrent.Executors

interface CapabilityHost {
    fun begin()
    fun end()
    fun receive(request: JSONObject, now: Long)
    fun drain(now: Long): List<JSONObject>
}

/** Session authority and typed dispatch are protocol-generic. All commits share one lock. */
class CapabilityRegistry(private val context: Context, private val discovery: CapabilityDiscovery, local: List<NativeCapabilities>) : CapabilityHost, AutoCloseable {
    private data class Provider(val contract: CapabilityContract, val local: NativeCapabilities? = null, val endpoint: CapabilityDiscovery.Endpoint? = null)
    private val locals = local.associateBy { it.contract.module }
    private var providers = locals.mapValues { Provider(it.value.contract, it.value) }
    private val grants = mutableSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private data class Pending(val id: Int, val key: String, val provider: Provider, val deadline: Long)
    private val pending = mutableMapOf<Int, Pending>()
    private val replies = mutableListOf<JSONObject>()
    private var session = ""
    private var nextId = 1
    private var active = false
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
            if (active) require(providers.all { (module, p) -> next[module]?.contract?.json()?.toString() == p.contract.json().toString() }) { "End the session before changing existing capability contracts" }
            providers = next; contracts()
        }
    }
    @Synchronized fun contracts(): List<CapabilityContract> = providers.values.map { it.contract }
    @Synchronized fun grant(module: String) { require(providers.containsKey(module)); grants.add(module) }
    @Synchronized fun revoke(module: String) {
        grants.remove(module)
        pending.values.filter { it.provider.contract.module == module }.toList().forEach { cancelRemote(it); fail(it.id, "REVOKED", "Capability access revoked") }
    }
    @Synchronized fun grantAll() { providers.keys.forEach(::grant) }
    @Synchronized fun revokeAll() { grants.toList().forEach(::revoke) }
    @Synchronized override fun begin() { check(!active && !closed); session = UUID.randomUUID().toString(); nextId = 1; active = true; pending.clear(); replies.clear() }
    private fun fail(id: Int, code: String, message: String) {
        pending.remove(id)
        replies.add(JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", code).put("message", message.take(200))))
    }
    private fun success(id: Int, value: Any) { pending.remove(id); replies.add(JSONObject().put("id", id).put("ok", true).put("value", value)) }
    @Synchronized override fun receive(request: JSONObject, now: Long) {
        check(active && !closed)
        val id = request.opt("id"); require(id is Int && id == nextId && nextId <= 128); nextId++
        val provider = providers[request.optString("module")]
        val function = provider?.contract?.functions?.singleOrNull { it.name == request.optString("function") }
        val args = request.optJSONArray("args")
        if (request.length() != 4 || provider == null || function == null || args == null) { fail(id, "BAD_REQUEST", "Unknown typed capability"); return }
        try { function.validateArguments(args) } catch (_: Exception) { fail(id, "BAD_REQUEST", "Invalid capability arguments"); return }
        if (provider.contract.module !in grants) { fail(id, "DENIED", "Capability access not granted"); return }
        if (pending.size >= 8) { fail(id, "LIMIT", "Too many pending requests"); return }
        if (provider.local != null) {
            try { success(id, provider.local.invoke(function.name, args)) }
            catch (error: Exception) { fail(id, "HOST_ERROR", error.message ?: "Host operation failed") }
            return
        }
        val endpoint = provider.endpoint!!
        val item = Pending(id, "$session:$id", provider, now + 5000)
        pending[id] = item
        val callback = object : ICapabilityResult.Stub() {
            private fun authenticate() { require(Binder.getCallingUid() == endpoint.uid && context.packageManager.getPackagesForUid(endpoint.uid)?.contains(endpoint.component.packageName) == true) { "Unauthenticated provider callback" } }
            override fun success(key: String, valueJson: String) = synchronized(this@CapabilityRegistry) {
                authenticate()
                if (pending[id] !== item || key != item.key || !active) return@synchronized
                try {
                    require(valueJson.toByteArray().size <= 16 * 1024)
                    val values = JSONArray(valueJson); require(values.length() == 1)
                    val value = values.get(0); function.result.validate(value)
                    success(id, value)
                } catch (_: Exception) { fail(id, "BAD_RESPONSE", "Provider response failed validation") }
            }
            override fun failure(key: String, code: String, message: String) = synchronized(this@CapabilityRegistry) {
                authenticate()
                if (pending[id] === item && key == item.key && active) {
                    val safe = if (code in setOf("PROVIDER_DENIED", "PROVIDER_REVOKED", "BAD_REQUEST")) code else "PROVIDER_ERROR"
                    fail(id, safe, message)
                }
            }
        }
        io.execute {
            try {
                endpoint.remote.invoke(item.key, function.name, args.toString(), callback)
                synchronized(this) { if (pending[id] !== item) endpoint.remote.cancel(item.key) }
            } catch (_: Exception) { synchronized(this) { if (pending[id] === item) fail(id, "PROVIDER_DIED", "Provider unavailable") } }
        }
    }
    private fun cancelRemote(item: Pending) { io.execute { try { item.provider.endpoint?.remote?.cancel(item.key) } catch (_: Exception) {} } }
    @Synchronized override fun drain(now: Long): List<JSONObject> {
        pending.values.filter { it.deadline <= now || it.provider.endpoint?.remote?.asBinder()?.isBinderAlive == false }.toList().forEach {
            cancelRemote(it); fail(it.id, if (it.deadline <= now) "TIMEOUT" else "PROVIDER_DIED", "Capability unavailable")
        }
        return replies.toList().also { replies.clear() }
    }
    @Synchronized override fun end() { pending.values.toList().forEach(::cancelRemote); pending.clear(); replies.clear(); active = false }
    @Synchronized override fun close() { if (closed) return; end(); closed = true; discovery.close(); io.shutdown() }
}
