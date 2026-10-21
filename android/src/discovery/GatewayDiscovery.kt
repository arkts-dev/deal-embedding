package dev.deal.embedding.discovery

import dev.deal.embedding.*

/** Uses the existing bounded, cancellable gateway transport; never falls back to local inference. */
internal class GatewayDiscovery(config: GenerationGatewayConfig) : DiscoveryInference {
    override val target = "Gateway · ${config.backend}"
    private val client = GatewayModelClient(config)
    private var active: GenerationCancellation? = null
    private var closed = false
    override fun complete(prompt: String, log: EmbeddingLog): String {
        val cancellation = synchronized(this) {
            check(!closed)
            check(active == null)
            GenerationCancellation().also { active = it }
        }
        try {
            return client.complete(prompt, "", "", cancellation, log)
        } finally {
            synchronized(this) { if (active === cancellation) active = null }
        }
    }
    @Synchronized override fun cancel() { active?.cancel() }
    @Synchronized override fun close() { if (!closed) { closed = true; cancel() } }
}
