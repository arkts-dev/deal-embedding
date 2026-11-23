package dev.deal.embedding

/** Native containment and the host-configured deadlines of one embedding instance.
 *
 * Every bound is finite and positive and is validated from host configuration before any work
 * starts. The provider boundary, the disclosure refresh and the sandboxes constructed from this block
 * take their deadlines from it, so those call sites never decide a deadline themselves; a sandbox
 * constructed without the block keeps these defaults. `evaluationTimeoutMs`, `heapBytes`,
 * `inputBytes` and `transferBytes` bound the sandbox, `providerTimeoutMs` bounds one provider call
 * and `discoveryTimeoutMs` bounds the binding wait of one disclosure refresh.
 *
 * Exhaustion is operational, never a source diagnostic that authorizes repair: a sandbox timeout
 * releases the sandbox and fails the operation, and a provider that passes its deadline fails with
 * the typed `TIMEOUT` completion.
 */
data class ExecutionLimits(
    val evaluationTimeoutMs: Long = 30_000,
    val heapBytes: Long = 64L * 1024 * 1024,
    val inputBytes: Int = 32 * 1024 * 1024,
    val transferBytes: Int = 16 * 1024 * 1024,
    /** One provider call, measured from the admission that issues it to its terminal reply. */
    val providerTimeoutMs: Long = 5_000,
    /** The binding wait of one disclosure refresh; the contract fetch that follows is not timed. */
    val discoveryTimeoutMs: Long = 5_000,
) {
    init {
        require(evaluationTimeoutMs > 0 && heapBytes > 0 && inputBytes > 0 && transferBytes > 0 &&
            providerTimeoutMs > 0 && discoveryTimeoutMs > 0) { "Execution limits and deadlines must be positive" }
    }

    /** The terminal instant of one provider call admitted at [now]. The adapter never decides it. */
    fun providerDeadline(now: Long): Long = now + providerTimeoutMs

    /** The terminal instant of one disclosure-refresh binding started at [now]. */
    fun discoveryDeadline(now: Long): Long = now + discoveryTimeoutMs
}
