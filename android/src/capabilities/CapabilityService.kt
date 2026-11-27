package dev.deal.embedding.capabilities

import dev.deal.embedding.*
import android.app.Service
import android.content.Intent
import android.os.*

/** Generic publication/invocation. Providers own handlers, caller policy and consent. */
abstract class CapabilityService : Service() {
    protected abstract val capabilities: NativeCapabilities
    protected abstract fun authorize(uid: Int): Boolean
    protected abstract fun consent(): Boolean
    /**
     * The provider application's own leg of the capability transport. The provider is a host here, so it
     * states the message bound and the lane width its handlers run on instead of inheriting an adapter
     * literal; the consuming embedding states the bound on its side ([ExecutionLimits.capabilityBytes]).
     */
    protected open val transport: ProviderTransport get() = ProviderTransport()
    /** Admission on the Binder thread, the handler on the bounded background lane, one typed reply. */
    private val dispatch by lazy { ProviderDispatch(capabilities, { consent() }, transport) }
    /** Providers call this when their own consent state changes, regardless of its storage. */
    protected fun consentChanged() { dispatch.revoke() }
    private fun caller(): Int = Binder.getCallingUid().also { if (!authorize(it)) throw SecurityException("Caller is not authorized by provider") }
    /**
     * One typed failure of this provider, published inside the transport's declared width. The provider's own
     * code and message cross Binder unchanged, so the consumer never has to substitute a code or a reason; the
     * consumer narrows them again to the bound its host configured. Every failure this service publishes is a
     * definite one: the provider answered, or refused before its handler ran, and either way the call itself
     * reached no further.
     */
    private fun failure(callback: ICapabilityResult, id: String, failure: ProviderFailure) {
        try { callback.failure(id, failure.code, failure.message) } catch (_: RemoteException) {}
    }
    /** One admitted call's reply, beside the waiting caller's lifetime: its death retires the call. */
    private inner class BinderReply(private val id: String, private val callback: ICapabilityResult) : ProviderDispatch.Reply {
        private val death = IBinder.DeathRecipient { dispatch.abandon(id) }
        override fun admitted(id: String) {
            try { callback.asBinder().linkToDeath(death, 0) } catch (_: Exception) { dispatch.abandon(id) }
        }
        override fun success(id: String, value: String) {
            try { callback.success(id, value) } catch (_: RemoteException) {}
        }
        override fun failure(id: String, failure: ProviderFailure) { this@CapabilityService.failure(callback, id, failure) }
        override fun retired(id: String) {
            try { callback.asBinder().unlinkToDeath(death, 0) } catch (_: Exception) {}
        }
    }
    private val binder = object : ICapabilityService.Stub() {
        override fun describe(): String { caller(); return capabilities.disclosure() }
        override fun invoke(id: String, name: String, arguments: String, callback: ICapabilityResult) {
            val uid = caller()
            dispatch.admit(uid, id, name, arguments, BinderReply(id, callback))
        }
        override fun cancel(id: String) { dispatch.cancel(caller(), id) }
    }
    final override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { dispatch.close(); super.onDestroy() }
}
