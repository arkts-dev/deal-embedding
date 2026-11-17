package dev.deal.embedding

/** Ordinary application setup; compiler distribution and candidate tests are private mechanisms. */
data class EmbeddingConfig(
    val storageName: String,
    val generation: GenerationGatewayConfig,
    val discovery: GenerationGatewayConfig? = null,
    val retainState: Boolean = false,
    val inference: InferenceProvider? = null,
    val execution: ExecutionLimits = ExecutionLimits(),
)

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
)
fun interface InferenceProvider {
    fun complete(request: InferenceRequest, cancellation: GenerationCancellation): String
}
