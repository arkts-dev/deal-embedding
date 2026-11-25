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
    /** One public probe: the byte bound of its response, the nesting bound of the selected document and
     * the wall-clock deadline of one read. A probe is a mechanism outside the provider boundary, so it
     * declares its own bounds rather than borrowing a provider deadline. */
    val probeBytes: Int = 65_536,
    val probeDepth: Int = 32,
    val probeTimeoutMs: Long = 5_000,
    /** The selected response characters one probe may read and the paths it may resolve from them. */
    val probeCharacters: Int = 6_000,
    val probePaths: Int = 3,
) {
    init {
        require(evaluationTimeoutMs > 0 && heapBytes > 0 && inputBytes > 0 && transferBytes > 0 &&
            providerTimeoutMs > 0 && discoveryTimeoutMs > 0) { "Execution limits and deadlines must be positive" }
        require(probeBytes > 0 && probeDepth > 0 && probeTimeoutMs > 0 && probeCharacters > 0 && probePaths > 0) {
            "Probe bounds and deadlines must be positive"
        }
    }

    /** The terminal instant of one provider call admitted at [now]. The adapter never decides it. */
    fun providerDeadline(now: Long): Long = now + providerTimeoutMs

    /** The terminal instant of one disclosure-refresh binding started at [now]. */
    fun discoveryDeadline(now: Long): Long = now + discoveryTimeoutMs
}
