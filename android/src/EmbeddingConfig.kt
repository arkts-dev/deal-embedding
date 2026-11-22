package dev.deal.embedding

/** Ordinary application setup; compiler distribution and candidate tests are private mechanisms. */
data class EmbeddingConfig(
    val storageName: String,
    val generation: GenerationGatewayConfig,
    val discovery: GenerationGatewayConfig? = null,
    val retainState: Boolean = false,
    val inference: InferenceProvider? = null,
    val execution: ExecutionLimits = ExecutionLimits(),
    /** Host presentation mode, fixed for this protocol; never supplied by generated source. */
    val target: String = "screen",
    /** Host-configured bound on concurrent `next` waits per Session; admission is compiled controller policy. */
    val updateWaits: Int = 8,
) {
    init {
        require(CompileInput.target(target) != null) { "Invalid host presentation target: $target" }
        // The rendering observer holds one wait per attached Session, so one slot must stay for the host.
        require(updateWaits in 2..64) { "Update wait limit must be 2-64" }
    }
}

/** Native containment, independent of generation/recovery policy. All limits are finite and positive. */
data class ExecutionLimits(
    val evaluationTimeoutMs: Long = 30_000,
    val heapBytes: Long = 64L * 1024 * 1024,
    val inputBytes: Int = 32 * 1024 * 1024,
    val transferBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(evaluationTimeoutMs > 0 && heapBytes > 0 && inputBytes > 0 && transferBytes > 0) { "Execution limits must be positive" }
    }
}

data class InferenceRequest(
    val input: String, val previous: String, val diagnostics: String,
    val operation: String, val stage: String, val ask: Int, val maxTokens: Int, val thinking: Boolean,
    /** JSON issued by compiled DEAL; empty for source/text inference. */
    val questions: String = "",
)
fun interface InferenceProvider {
    fun complete(request: InferenceRequest, cancellation: GenerationCancellation): String
}
