package dev.deal.embedding.capabilities

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.*

/** Generic publication/invocation. Providers own handlers, caller policy and consent. */
abstract class CapabilityService : Service() {
    protected abstract val capabilities: NativeCapabilities
    protected abstract fun authorize(uid: Int): Boolean
    protected abstract fun consent(): Boolean
    protected open val delayMillis: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private data class Work(val uid: Int, val callback: ICapabilityResult, val death: IBinder.DeathRecipient, val runnable: Runnable)
    private val pending = mutableMapOf<String, Work>()
    /** Providers call this when their own consent state changes, regardless of its storage. */
    @Synchronized protected fun consentChanged() {
        if (!consent()) pending.keys.toList().forEach { id -> remove(id)?.let { failure(it.callback, id, "PROVIDER_REVOKED", "Provider access revoked") } }
    }
    private fun caller(): Int = Binder.getCallingUid().also { if (!authorize(it)) throw SecurityException("Caller is not authorized by provider") }
    private fun remove(id: String): Work? = pending.remove(id)?.also {
        handler.removeCallbacks(it.runnable); try { it.callback.asBinder().unlinkToDeath(it.death, 0) } catch (_: Exception) {}
    }
    private fun failure(callback: ICapabilityResult, id: String, code: String, message: String) { try { callback.failure(id, code, message.take(200)) } catch (_: RemoteException) {} }
    private val binder = object : ICapabilityService.Stub() {
        override fun describe(): String { caller(); return capabilities.contract.json().toString() }
        override fun invoke(id: String, name: String, arguments: String, callback: ICapabilityResult) = synchronized(this@CapabilityService) {
            val uid = caller()
            require(id.matches(Regex("[a-f0-9-]{36}:[0-9]{1,3}")) && arguments.toByteArray().size <= 16 * 1024)
            if (!consent()) { failure(callback, id, "PROVIDER_DENIED", "Provider owner has not allowed access"); return@synchronized }
            require(pending.size < 8 && !pending.containsKey(id))
            val args = JSONArray(arguments)
            capabilities.contract.functions.single { it.name == name }.validateArguments(args)
            val death = IBinder.DeathRecipient { synchronized(this@CapabilityService) { remove(id) } }
            val runnable = Runnable { synchronized(this@CapabilityService) {
                val item = remove(id) ?: return@synchronized
                if (!consent()) failure(item.callback, id, "PROVIDER_REVOKED", "Provider access revoked")
                else try {
                    val result = JSONArray().put(capabilities.invoke(name, args)).toString()
                    require(result.toByteArray().size <= 16 * 1024)
                    item.callback.success(id, result)
                } catch (_: RemoteException) {} catch (_: Exception) { failure(item.callback, id, "PROVIDER_ERROR", "Provider operation failed") }
            } }
            callback.asBinder().linkToDeath(death, 0)
            pending[id] = Work(uid, callback, death, runnable)
            handler.postDelayed(runnable, delayMillis)
        }
        override fun cancel(id: String) = synchronized(this@CapabilityService) { val uid = caller(); if (pending[id]?.uid == uid) remove(id); Unit }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { synchronized(this) { pending.keys.toList().forEach { remove(it) } }; super.onDestroy() }
}
