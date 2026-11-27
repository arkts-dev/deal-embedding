package dev.deal.embedding.capabilities

/**
 * The bounds one provider application declares for its own leg of the capability transport.
 *
 * A provider process is a host too: the consuming embedding bounds the same messages from its side
 * (`ExecutionLimits.capabilityBytes`), and the provider states the bound its own leg accepts and
 * publishes instead of keeping an adapter literal. [messageBytes] bounds the arguments of one request
 * and the value of one reply, measured as the UTF-8 bytes of their JSON encoding; a message past it is
 * refused, typed and located, and nothing is truncated.
 *
 * [lanes] bounds how many provider handlers run at once. Every handler runs on that background lane and
 * never on the provider application's UI thread, so a handler that blocks (network, disk, binder, a
 * database) cannot freeze the provider's UI while the consuming host still observes its own configured
 * deadline (`docs/spec-v0.8.md:90`, "Blocking adapters run off the UI thread").
 *
 * The defaults are the bounds the two legs had as literals, so an unconfigured provider keeps the
 * transport it had while a host that states wider or narrower bounds gets them.
 */
data class ProviderTransport(val messageBytes: Int = 16 * 1024, val lanes: Int = 4) {
    init {
        require(messageBytes > 0) { "Provider transport bound must be positive" }
        require(lanes > 0) { "Provider lane width must be positive" }
    }
}
