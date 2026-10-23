package dev.deal.embedding

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Injection seam for alternative inference mechanisms and deterministic tests. Gateway integration is library-owned.
 * Source completions return raw DEAL-SOURCE/1 or DEAL-PATCH/1 text; previous is an internally serialized pair,
 * not an instruction to emit JSON. Catalogue choice calls retain their independently specified JSON contract.
 */
interface ModelClient {
    fun complete(input: String, previous: String, diagnostics: String, cancellation: GenerationCancellation, log: EmbeddingLog): String
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
