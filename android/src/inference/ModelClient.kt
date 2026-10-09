package dev.deal.embedding

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Host-selected inference mechanism; no transport or credential policy belongs here. */
interface ModelClient {
    fun complete(input: String, previous: String, diagnostics: String, cancellation: GenerationCancellation): String
    fun completeLogged(input: String, previous: String, diagnostics: String, cancellation: GenerationCancellation, log: EmbeddingLog): String = complete(input, previous, diagnostics, cancellation)
}

class GenerationCancellation {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = mutableSetOf<() -> Unit>()
    @Synchronized fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            val active = callbacks.toList(); callbacks.clear()
            active.forEach { runCatching { it() } }
        }
    }
    fun check() { if (cancelled.get()) throw CancellationException("Generation cancelled") }
    /** Registration is atomic with cancellation. Hosts release it when their operation ends. */
    @Synchronized fun onCancel(action: () -> Unit): AutoCloseable {
        check()
        callbacks.add(action)
        return AutoCloseable { synchronized(this) { callbacks.remove(action) }; Unit }
    }
}
