package dev.deal.embedding.discovery

import dev.deal.embedding.ApplicationProtocol

/** Host policy and persistence; no app storage or navigation implementation enters the library. */
interface AssistanceHooks {
    fun generationEnabled(): Boolean
    fun discoveryEnabled(): Boolean
    fun applyGrants(protocol: ApplicationProtocol): List<String>
    fun saved(identity: String): String?
    fun save(identity: String, experience: String)
    fun dismissed(identity: String): Boolean
    fun dismiss(identity: String)
    fun resetting(): Boolean
    fun register(owner: Any, stop: () -> Unit)
    fun unregister(owner: Any)
    /** Native authority may reject stale app records before mounting or retaining a result. */
    fun validateCurrent() {}
    fun activating() {}
}
