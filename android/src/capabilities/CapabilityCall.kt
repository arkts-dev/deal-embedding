package dev.deal.embedding.capabilities

import dev.deal.embedding.CapabilityContract
import dev.deal.embedding.CapabilityFunction
import dev.deal.embedding.InvocationContext
import org.json.JSONArray

/**
 * The host facts one send admission is decided with, published by the live Session: the host's read or
 * mutating classification of every accepted function, the host confirmations bound to one target and
 * validated argument snapshot, the Experience origin and the frozen-request determination. Facts, never
 * decisions: the boundary decides with them exactly as compiled policy in [core/execution.deal]
 * `mutationAuthority`/`confirmationCovers` does. A determination the host never issued is false, so it
 * authorizes no mutation.
 */
internal class CapabilityFacts(
    val classification: Map<String, String>,
    val confirmation: Map<String, String>,
    val origin: String,
    val requestAuthorizes: Boolean,
    val requestTarget: String,
)

/** The located refusal of one call: the reply code and the reason it publishes. */
internal class Refusal(val code: String, val reason: String)

/**
 * The host's class of one disclosed function: the registration's own statement, else the class its
 * disclosed contract carries. A function that states neither is mutating, because a missing
 * classification must never authorize a write.
 */
internal fun hostClass(function: CapabilityFunction, registered: Map<String, String>): String {
    val value = registered[function.name]?.takeIf { it.isNotEmpty() } ?: function.classification
    return if (value == "read") "read" else "mutating"
}

/**
 * The classification facts of every accepted provider, keyed `module/function`, derived from each
 * provider's own source: a local registration states its per-function class beside its contract, and a
 * discovered endpoint states the class inside the contract it disclosed. One derivation for both, so a
 * discovered read is read as a read exactly like a locally registered one and a function that states
 * neither stays mutating.
 */
internal fun acceptedClasses(providers: List<Pair<CapabilityContract, Map<String, String>>>): Map<String, String> {
    val classes = mutableMapOf<String, String>()
    for ((contract, registered) in providers) {
        for (function in contract.functions) { classes[contract.module + "/" + function.name] = hostClass(function, registered) }
    }
    return classes
}

/**
 * The send boundary of [spec v0.8](docs/spec-v0.8.md) "Provider bindings". Disclosure and a module
 * grant never authorize a mutation, so a call is decided by the same precedence as compiled policy:
 * a host-confirmed denial is final; the frozen request's determination binds one exact operation and
 * target; an approved confirmation binds one target and one validated argument snapshot; an
 * unclassified function is mutating. Every other call is refused before it is dispatched, so no stale
 * or unauthorized call is published.
 */
internal class CapabilityCall(private val facts: () -> CapabilityFacts) {
    /** The host's classification of one disclosed function. An unclassified function is mutating: a
     * wrong read classification would silently grant a write, so no classification authorizes a read. */
    fun classification(module: String, function: String): String =
        if (facts().classification[module + "/" + function] == "read") "read" else "mutating"

    private fun requestDetermines(module: String, function: String): Boolean {
        val live = facts()
        return live.origin == "generated" && live.requestAuthorizes && live.requestTarget == module + "/" + function
    }

    /**
     * The admission of one call: null admits it, a [Refusal] refuses it before anything is dispatched.
     * The arguments are the validated snapshot the call will send, so an approval of another payload
     * authorizes nothing even when it names the same operation and target.
     */
    fun admit(module: String, function: String, argumentsJson: String, granted: Boolean): Refusal? {
        if (!granted) { return Refusal("DENIED", "Capability access not granted") }
        if (classification(module, function) == "read") { return null }
        val confirmation = facts().confirmation
        val key = module + "/" + function
        val status = confirmation[key + "#" + argumentsJson] ?: ""
        if (status == "denied") { return Refusal("DENIED", "Host denied the mutation") }
        if (requestDetermines(module, function)) { return null }
        if (status == "stale") { return Refusal("DENIED", "Confirmation no longer correlates") }
        if (status == "approved") { return null }
        // Another snapshot of this same operation: the host approved one payload, so this one is not
        // covered. A stale or denied approval of the target keeps its own reason.
        val target = confirmation.entries.filter { it.key.startsWith("$key#") }
        if (target.any { it.value == "approved" }) { return Refusal("DENIED", "Confirmation approves another argument snapshot") }
        if (target.any { it.value == "stale" }) { return Refusal("DENIED", "Confirmation no longer correlates") }
        if (target.any { it.value == "denied" }) { return Refusal("DENIED", "Host denied the mutation") }
        if (confirmation.any { it.value == "approved" }) { return Refusal("DENIED", "Approval names another operation or target") }
        return Refusal("DENIED", "Mutation requires host confirmation")
    }

    /** The validated value of one admitted local call, dispatched only after [admit] returned null. */
    fun invoke(provider: NativeCapabilities, function: CapabilityFunction, arguments: JSONArray, invocation: InvocationContext): Any =
        provider.invoke(function.name, arguments, invocation)
}
