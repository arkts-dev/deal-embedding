package dev.deal.embedding.capabilities

import dev.deal.embedding.CapabilityContract
import dev.deal.embedding.CapabilityFunction
import dev.deal.embedding.ExecutionLimits
import dev.deal.embedding.Parameter
import dev.deal.embedding.WireType
import org.json.JSONArray
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The executed matrix of the capability transport of spec v0.8 "Execution and provider workflows"
 * (`docs/spec-v0.8.md:90`, "Blocking adapters run off the UI thread") and its host-configured transport
 * bound: a provider handler runs on a bounded background lane, the admitting thread returns at once, one
 * call admits one terminal reply, cancellation and consent revocation retire a call so a later result
 * publishes nothing, and a message past the configured bound is refused, typed and located, before any
 * work.
 *
 * The `CapabilityService` around this queue is an Android Service over Binder, so its call sites are
 * asserted from production source by `tests/tools/test-provider-transport.py`; the queue, the lane, the
 * bound and the reply decisions executed here are the production units both of those call sites use.
 */
private var checked = 0
private fun need(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
    checked += 1
}
private fun rejects(block: () -> Unit, message: () -> String) {
    if (runCatching { block() }.isFailure) checked += 1 else throw AssertionError(message())
}

private val text = WireType("string", maximum = 8192)
private fun probe(handler: (JSONArray) -> Any) = NativeCapabilities(
    CapabilityContract("host/probe", "Probe provider",
        listOf(CapabilityFunction("read", "Read the fixture value", listOf(Parameter("value", text)), text)), 1),
    mapOf("read" to handler), mapOf("read" to "read"))
private val owner = 7
private fun call(sequence: Int) = "01234567-89ab-cdef-0123-456789abcdef:$sequence"
/** Exactly [bytes] UTF-8 bytes of a one-element JSON array holding a string. */
private fun arguments(bytes: Int) = "[\"" + "a".repeat(bytes - 4) + "\"]"

/** What one call published, and the bookkeeping calls around it. */
private class Recorder : ProviderDispatch.Reply {
    private val settled = LinkedBlockingQueue<Triple<String, String, String>>()
    val admitted = AtomicInteger()
    val retired = AtomicInteger()
    override fun admitted(id: String) { admitted.incrementAndGet() }
    override fun success(id: String, value: String) { settled.add(Triple("ok", id, value)) }
    override fun failure(id: String, failure: ProviderFailure) { settled.add(Triple(failure.code, id, failure.message)) }
    override fun retired(id: String) { retired.incrementAndGet() }
    fun poll(milliseconds: Long = 5_000): Triple<String, String, String>? = settled.poll(milliseconds, TimeUnit.MILLISECONDS)
    fun quiet(milliseconds: Long): Boolean = settled.poll(milliseconds, TimeUnit.MILLISECONDS) == null
    /** Wait until [count] replies have retired their bookkeeping, which follows their reply by contract. */
    fun awaitRetired(count: Int = 1, milliseconds: Long = 5_000) {
        val end = System.currentTimeMillis() + milliseconds
        while (retired.get() < count && System.currentTimeMillis() < end) Thread.sleep(1)
    }
}

/** A handler that blocks until its gate opens, and records the thread it ran on. */
private class Blocking {
    private val started = java.util.concurrent.CountDownLatch(1)
    private val gate = java.util.concurrent.CountDownLatch(1)
    val thread = AtomicReference("")
    val runs = AtomicInteger()
    fun handler(): (JSONArray) -> Any = {
        runs.incrementAndGet()
        thread.set(Thread.currentThread().name)
        started.countDown()
        gate.await(10, TimeUnit.SECONDS)
        "done"
    }
    fun awaitStart() = check(started.await(5, TimeUnit.SECONDS)) { "the blocking handler never started" }
    fun release() = gate.countDown()
}

fun main() {
    // Both legs state the same bound by default, so an unconfigured provider keeps the transport the
    // consumer expects, and each bound is host configuration validated before any work.
    val defaults = ProviderTransport()
    need(defaults.messageBytes == ExecutionLimits().capabilityBytes) { "the two legs must declare the same default transport bound" }
    need(defaults.lanes > 0) { "the lane width must be a positive thread count" }
    rejects({ ProviderTransport(messageBytes = 0) }) { "a nonpositive provider transport bound must be refused" }
    rejects({ ProviderTransport(lanes = 0) }) { "a nonpositive lane width must be refused" }
    rejects({ ExecutionLimits(capabilityBytes = 0) }) { "the consuming host must refuse a nonpositive capability bound" }
    need(ExecutionLimits(capabilityBytes = 64).capabilityBytes == 64) { "a host-configured capability bound must be the block's bound" }

    // Acceptance at the boundary: a handler's value inside the bound is published as the one success of
    // its call, and both terminal bookkeeping hooks run exactly once.
    run {
        val dispatch = ProviderDispatch(probe { "fine" }, { true }, ProviderTransport(messageBytes = 64, lanes = 1))
        val recorder = Recorder()
        dispatch.admit(owner, call(1), "read", arguments(64), recorder)
        val reply = recorder.poll() ?: throw AssertionError("a message inside the configured bound published no reply")
        need(reply.first == "ok" && reply.third == "[\"fine\"]") { "a message inside the configured bound was not published: " + reply }
        // Retirement follows the reply, so the published reply alone does not yet prove the bookkeeping ran.
        recorder.awaitRetired()
        need(recorder.admitted.get() == 1 && recorder.retired.get() == 1) { "an admitted call must be published and retired exactly once" }
        dispatch.close()
    }

    // Rejection at the boundary: one byte over the configured bound is a typed, located refusal before
    // any work exists, and the handler never runs.
    run {
        val runs = AtomicInteger()
        val dispatch = ProviderDispatch(probe { runs.incrementAndGet(); "fine" }, { true }, ProviderTransport(messageBytes = 64, lanes = 1))
        val recorder = Recorder()
        dispatch.admit(owner, call(1), "read", arguments(65), recorder)
        val refusal = recorder.poll() ?: throw AssertionError("an over-bound request published no refusal")
        need(refusal.first == "LIMIT") { "an over-bound request must be a typed LIMIT refusal: " + refusal }
        need(refusal.third.contains("64")) { "the over-bound refusal must name the configured bound: " + refusal.third }
        need(runs.get() == 0) { "an over-bound request must be refused before the handler runs" }
        need(recorder.admitted.get() == 0 && recorder.retired.get() == 0) { "a refused admission must never be admitted or retired" }
        // The bound is the host's: the same message is accepted when the host declares a wider one.
        val wider = ProviderDispatch(probe { "fine" }, { true }, ProviderTransport(messageBytes = 65, lanes = 1))
        val second = Recorder()
        wider.admit(owner, call(2), "read", arguments(65), second)
        need(second.poll()?.first == "ok") { "a host that raises its bound must accept the same message" }
        wider.close()
        dispatch.close()
    }

    // A consent denial is the provider owner's own refusal, typed and located, and no handler runs.
    run {
        val runs = AtomicInteger()
        val dispatch = ProviderDispatch(probe { runs.incrementAndGet(); "fine" }, { false }, ProviderTransport(lanes = 1))
        val recorder = Recorder()
        dispatch.admit(owner, call(1), "read", arguments(12), recorder)
        need(recorder.poll()?.first == "PROVIDER_DENIED") { "a consent denial must stay its own classified refusal" }
        need(runs.get() == 0) { "a denied call must never reach the provider handler" }
        dispatch.close()
    }

    // The handler runs off the admitting thread, on the provider lane: admitting a blocking handler
    // returns at once, and the reply arrives only after the handler settles.
    run {
        val blocking = Blocking()
        val dispatch = ProviderDispatch(probe(blocking.handler()), { true }, ProviderTransport(lanes = 2))
        val recorder = Recorder()
        val started = System.nanoTime()
        dispatch.admit(owner, call(1), "read", arguments(12), recorder)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        need(elapsedMs < 1_000) { "admitting a blocking handler blocked the caller for ${elapsedMs}ms" }
        blocking.awaitStart()
        need(blocking.thread.get().startsWith("deal-provider-")) { "the handler did not run on the provider lane: " + blocking.thread.get() }
        need(blocking.thread.get() != Thread.currentThread().name) { "the handler ran on the admitting thread" }
        need(recorder.quiet(100)) { "a blocking handler published a reply before it settled" }
        blocking.release()
        need(recorder.poll()?.first == "ok") { "a handler that settled on the lane published no result" }
        dispatch.close()
    }

    // The lane is bounded but not serial: while one handler blocks, another admitted call settles, so a
    // slow provider call neither freezes the caller nor starves the next one.
    run {
        val blocking = Blocking()
        val dispatch = ProviderDispatch(probe { if (it.getString(0) == "slow") blocking.handler()(it) else "fast" }, { true }, ProviderTransport(lanes = 2))
        val slow = Recorder()
        val fast = Recorder()
        dispatch.admit(owner, call(1), "read", "[\"slow\"]", slow)
        blocking.awaitStart()
        dispatch.admit(owner, call(2), "read", "[\"quick\"]", fast)
        need(fast.poll()?.third == "[\"fast\"]") { "a second call did not settle while the first handler blocked" }
        need(slow.quiet(100)) { "the blocking call settled before its handler did" }
        blocking.release()
        need(slow.poll()?.first == "ok") { "the blocking call never settled after its handler did" }
        dispatch.close()
    }

    // One lane thread is a bound on concurrency: with a single lane the next call waits for the one
    // running, so the width the host declared is the width the handlers use.
    run {
        val blocking = Blocking()
        val runs = AtomicInteger()
        val dispatch = ProviderDispatch(probe { if (runs.incrementAndGet() == 1) blocking.handler()(it) else "second" }, { true }, ProviderTransport(lanes = 1))
        val first = Recorder()
        val second = Recorder()
        dispatch.admit(owner, call(1), "read", "[\"one\"]", first)
        blocking.awaitStart()
        dispatch.admit(owner, call(2), "read", "[\"two\"]", second)
        need(second.quiet(200)) { "a one-thread lane ran two handlers at once" }
        blocking.release()
        need(first.poll()?.first == "ok" && second.poll()?.first == "ok") { "a bounded lane lost a call it had queued" }
        dispatch.close()
    }

    // Cancellation retires a queued call: it publishes nothing, its bookkeeping is released, and a
    // handler still running later publishes nothing either.
    run {
        val blocking = Blocking()
        val dispatch = ProviderDispatch(probe(blocking.handler()), { true }, ProviderTransport(lanes = 1))
        val recorder = Recorder()
        dispatch.admit(owner, call(1), "read", arguments(12), recorder)
        blocking.awaitStart()
        dispatch.cancel(owner, call(1))
        need(recorder.retired.get() == 1) { "a cancelled call must release its bookkeeping once" }
        blocking.release()
        need(recorder.quiet(200)) { "a cancelled call published a late result" }
        // Another caller may not cancel this provider's work.
        val other = Recorder()
        dispatch.admit(owner, call(2), "read", arguments(12), other)
        dispatch.cancel(owner + 1, call(2))
        need(other.poll()?.first == "ok") { "a caller cancelled a call it does not own" }
        dispatch.close()
    }

    // A revoked consent retires the call with the provider's own revocation and no later result
    // publishes.
    run {
        val allowed = AtomicReference(true)
        val blocking = Blocking()
        val dispatch = ProviderDispatch(probe(blocking.handler()), { allowed.get() }, ProviderTransport(lanes = 1))
        val recorder = Recorder()
        dispatch.admit(owner, call(1), "read", arguments(12), recorder)
        blocking.awaitStart()
        allowed.set(false)
        dispatch.revoke()
        need(recorder.poll()?.first == "PROVIDER_REVOKED") { "a revoked consent must publish the provider's own revocation" }
        blocking.release()
        need(recorder.quiet(200)) { "a call retired by revocation published a late result" }
        need(recorder.retired.get() == 1) { "a revoked call must release its bookkeeping once" }
        dispatch.close()
    }

    // The configured deadline is the consuming host's and still applies: a blocked provider thread
    // cannot postpone it, and the call the host retired at its deadline publishes nothing when the
    // provider finally answers.
    run {
        val limits = ExecutionLimits(providerTimeoutMs = 200)
        val blocking = Blocking()
        val dispatch = ProviderDispatch(probe(blocking.handler()), { true }, ProviderTransport(lanes = 1))
        val recorder = Recorder()
        val admittedAt = System.currentTimeMillis()
        dispatch.admit(owner, call(1), "read", arguments(12), recorder)
        blocking.awaitStart()
        val deadline = limits.providerDeadline(admittedAt)
        while (System.currentTimeMillis() <= deadline) Thread.sleep(5)
        need(blocking.runs.get() == 1) { "the provider handler was not running at the configured deadline" }
        need(recorder.quiet(50)) { "a blocked provider published a result at the configured deadline" }
        dispatch.cancel(owner, call(1))
        blocking.release()
        need(recorder.quiet(200)) { "a reply after the configured deadline was published" }
        dispatch.close()
    }

    // A closed service retires its queued work and admits nothing more.
    run {
        val dispatch = ProviderDispatch(probe { "fine" }, { true }, ProviderTransport(lanes = 1))
        val recorder = Recorder()
        val blocking = Blocking()
        val held = ProviderDispatch(probe(blocking.handler()), { true }, ProviderTransport(lanes = 1))
        val pending = Recorder()
        held.admit(owner, call(1), "read", arguments(12), pending)
        blocking.awaitStart()
        held.close()
        blocking.release()
        need(pending.quiet(200)) { "a closed provider service published a late result" }
        rejects({ dispatch.close(); dispatch.admit(owner, call(9), "read", arguments(12), recorder) }) {
            "a closed provider service admitted a call"
        }
    }

    println("capability transport: $checked properties over the provider lane, the transport bound and one terminal reply")
    println("configured deadlines still apply: the provider lane holds only calls the consuming host no longer waits for")
}
