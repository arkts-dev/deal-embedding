package dev.deal.embedding

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** One transport dispatch. Stage, token and reasoning policy arrive from compiled DEAL. */
internal interface ModelClient {
    fun complete(request: InferenceRequest, cancellation: GenerationCancellation, log: EmbeddingLog): String
    fun dispatch(request: InferenceRequest, cancellation: GenerationCancellation, log: EmbeddingLog): InferenceResponse =
        InferenceResponse(complete(request,cancellation,log),"unknown","")
}

internal data class InferenceResponse(val content: String, val finish: String, val refusal: String)

class GenerationCancellation {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = mutableSetOf<() -> Unit>()
    @Synchronized internal fun cancel() {
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
