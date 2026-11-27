package dev.deal.embedding.capabilities

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bounded background lane of one provider process: every provider handler runs here, never on the
 * caller's thread and never on the provider application's UI thread.
 *
 * A handler that blocks for as long as its work takes therefore holds a lane thread only. The Binder
 * caller returns from the invocation at once, the provider's UI thread keeps running, and the consuming
 * host decides the call's terminal instant with its own configured deadline instead of waiting on a
 * frozen UI. [width] threads run handlers at once and the owning dispatch bounds how many calls queue;
 * cancelling a call before it starts means it never runs, and closing the lane stops it.
 */
internal class ProviderLane(width: Int) : AutoCloseable {
    private val threads = AtomicInteger()
    private val workers: ExecutorService = Executors.newFixedThreadPool(width) { work ->
        Thread(work, "deal-provider-" + threads.incrementAndGet()).apply { isDaemon = true }
    }

    /** Queue one handler run; the caller is never blocked by the work it submitted. */
    fun submit(work: Runnable): Future<*> = workers.submit(work)

    override fun close() { workers.shutdownNow() }
}
