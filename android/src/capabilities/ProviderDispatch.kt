package dev.deal.embedding.capabilities

import dev.deal.embedding.CAPABILITY_SLOTS
import dev.deal.embedding.ProtocolFailure
import dev.deal.embedding.validateArguments
import org.json.JSONArray
import java.util.concurrent.Future

/**
 * The invocation queue of one provider process: admission on the Binder thread, the handler on a
 * bounded background lane, and one typed terminal reply through the serialized callback.
 *
 * Admission is the whole of what runs on the caller's thread. It validates the call identity, refuses a
 * message past the host-configured transport bound ([ProviderTransport.messageBytes]) and a call the
 * provider owner does not consent to, both typed and located and before any work exists, then queues the
 * handler on the lane. The handler is therefore never invoked on the provider application's UI thread
 * and never while this queue's lock is held, so a blocking provider neither freezes its own UI nor
 * delays another call's admission (`docs/spec-v0.8.md:90`).
 *
 * One call admits exactly one terminal reply. Cancellation, a revoked consent, the death of the waiting
 * caller and the shutdown of the service retire a call so a result that arrives later publishes nothing;
 * a call retired before its handler started never runs it. The consuming host owns the terminal instant
 * of a call with its own configured deadline, and nothing here can extend it: the lane holds only work
 * the host has already stopped waiting for.
 */
internal class ProviderDispatch(
    private val capabilities: NativeCapabilities,
    private val consent: () -> Boolean,
    private val transport: ProviderTransport,
) : AutoCloseable {
    /** One terminal reply of an admitted call, published through the transport's serialized callback. */
    internal interface Reply {
        /** The call is queued on the lane: its owner may now attach its own lifetime bookkeeping. */
        fun admitted(id: String)
        fun success(id: String, value: String)
        fun failure(id: String, failure: ProviderFailure)
        /** The call left the queue: called exactly once, after any reply and never after a refused admission. */
        fun retired(id: String)
    }

    private class Work(val uid: Int, val reply: Reply) { var future: Future<*>? = null }

    private val lane = ProviderLane(transport.lanes)
    private val pending = mutableMapOf<String, Work>()
    private var closed = false

    /**
     * Admission of one Binder invocation on the caller's thread. Nothing here runs the handler: a message
     * past the configured transport bound is a typed refusal before any work, a consent denial is the
     * provider owner's own refusal, and anything else is queued for the lane.
     */
    @Synchronized fun admit(uid: Int, id: String, name: String, arguments: String, reply: Reply) {
        check(!closed) { "Provider dispatch is closed" }
        require(id.matches(CALL)) { "Invalid capability call identity: $id" }
        val sequence = id.substringAfter(':').toLongOrNull()
        require(sequence != null && sequence <= Int.MAX_VALUE) { "Invalid capability call sequence: $id" }
        if (arguments.toByteArray().size > transport.messageBytes) {
            reply.failure(id, ProviderFailure.refused(
                "LIMIT", "Capability request exceeds the configured transport bound of ${transport.messageBytes} bytes", FailureBounds.TRANSPORT
            ))
            return
        }
        if (!consent()) {
            reply.failure(id, ProviderFailure.refused("PROVIDER_DENIED", "Provider owner has not allowed access", FailureBounds.TRANSPORT))
            return
        }
        require(pending.size < CAPABILITY_SLOTS && !pending.containsKey(id)) { "Too many pending provider calls" }
        val function = capabilities.contract.functions.singleOrNull { it.name == name }
        if (function == null) {
            reply.failure(id, ProviderFailure.answered(ProtocolFailure("INVALID_ARGUMENT", "Undisclosed capability function '$name'"), FailureBounds.TRANSPORT))
            return
        }
        val args = try { JSONArray(arguments) } catch (error: Exception) {
            reply.failure(id, ProviderFailure.answered(error, FailureBounds.TRANSPORT))
            return
        }
        try { function.validateArguments(args) } catch (error: Exception) {
            reply.failure(id, ProviderFailure.answered(error, FailureBounds.TRANSPORT))
            return
        }
        val work = Work(uid, reply)
        pending[id] = work
        reply.admitted(id)
        work.future = lane.submit { run(id, work, name, args) }
    }

    /**
     * The one handler run of an admitted call, on the lane. Consent is rechecked before the handler is
     * reached and the result is published only while this work still owns the call, so a revocation or a
     * cancellation that happened in between publishes its own outcome and never this handler's result.
     */
    private fun run(id: String, work: Work, name: String, args: JSONArray) {
        if (!live(id, work)) return
        if (!consent()) { refuse(id, work, ProviderFailure.refused("PROVIDER_REVOKED", "Provider access revoked", FailureBounds.TRANSPORT)); return }
        val value = try { JSONArray().put(capabilities.invoke(name, args)).toString() } catch (error: Exception) {
            refuse(id, work, ProviderFailure.answered(error, FailureBounds.TRANSPORT))
            return
        }
        // The handler answered, so the provider's own code and message stand; a reply this leg cannot
        // publish is the answered call's own failure, typed and located, and is never truncated.
        if (value.toByteArray().size > transport.messageBytes) {
            refuse(id, work, ProviderFailure.answered(
                "LIMIT", "Capability response exceeds the configured transport bound of ${transport.messageBytes} bytes", FailureBounds.TRANSPORT
            ))
            return
        }
        publish(id, work) { it.reply.success(id, value) }
    }

    /** A call the caller asked to cancel: it publishes nothing and, if it had not started, never runs. */
    fun cancel(uid: Int, id: String) {
        val work = synchronized(this) { pending[id]?.takeIf { it.uid == uid } } ?: return
        retire(id, work)
    }

    /** The death of the waiting caller: its call is retired exactly as a cancellation retires it. */
    fun abandon(id: String) {
        val work = synchronized(this) { pending[id] } ?: return
        retire(id, work)
    }

    /**
     * The provider owner's consent changed: every call that has not answered is refused with the
     * provider's own revocation, and an already running handler publishes nothing. Provider consent is
     * independent of the host's own approval, so this refusal is definite and changes no business state.
     */
    fun revoke() {
        val refused = synchronized(this) { pending.entries.map { it.key to it.value }.also { pending.clear() } }
        for ((id, work) in refused) {
            work.future?.cancel(false)
            work.reply.failure(id, ProviderFailure.refused("PROVIDER_REVOKED", "Provider access revoked", FailureBounds.TRANSPORT))
            work.reply.retired(id)
        }
    }

    override fun close() {
        val retired = synchronized(this) { closed = true; pending.entries.map { it.key to it.value }.also { pending.clear() } }
        for ((id, work) in retired) { work.future?.cancel(false); work.reply.retired(id) }
        lane.close()
    }

    private fun live(id: String, work: Work): Boolean = synchronized(this) { !closed && pending[id] === work }

    private fun retire(id: String, work: Work) {
        if (!synchronized(this) { pending.remove(id).let { it === work } }) return
        work.future?.cancel(false)
        work.reply.retired(id)
    }

    private fun refuse(id: String, work: Work, failure: ProviderFailure) = publish(id, work) { it.reply.failure(id, failure) }

    /** Publish the one terminal reply of the one call this work still owns, then release its bookkeeping. */
    private fun publish(id: String, work: Work, reply: (Work) -> Unit) {
        if (!synchronized(this) { pending.remove(id).let { it === work } }) return
        try { reply(work) } finally { work.reply.retired(id) }
    }

    private companion object {
        /** The call identity the consuming registry issues: a Session identity and its sequence. */
        val CALL = Regex("[a-f0-9-]{36}:[1-9][0-9]{0,9}")
    }
}
