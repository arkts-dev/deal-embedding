package dev.deal.embedding

/** Explicit app-linked catalogue policy, packaged by the consuming application. */
data class CataloguePolicy(val assetNamespace: String) {
    init { require(assetNamespace.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))) }
}

/** Ordinary application setup; compiler distribution and candidate tests are private mechanisms. */
data class EmbeddingConfig(
    val storageName: String,
    val generation: GenerationGatewayConfig,
    val discovery: GenerationGatewayConfig? = null,
    val retainState: Boolean = false,
    val policy: CataloguePolicy? = null,
    val inference: InferenceProvider? = null,
)

data class InferenceRequest(val input: String, val previous: String, val diagnostics: String)
fun interface InferenceProvider {
    fun complete(request: InferenceRequest, cancellation: GenerationCancellation): String
}
