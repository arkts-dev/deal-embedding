package dev.deal.embedding.bounds

import dev.deal.embedding.ExecutionLimits

/**
 * Host-configured containment and deadlines of spec v0.8 "Execution and provider workflows".
 *
 * The assertions derive from the criterion and from the behaviour this change replaces, not from the
 * class: every bound is finite and positive, a nonpositive bound is rejected before any work starts,
 * the provider and discovery deadlines are values the host supplies rather than adapter literals, and
 * an unconfigured host keeps the deadline the adapters used to hard-code.
 */
private fun rejects(what: String, block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("$what was accepted")
}

fun main() {
    val defaults = ExecutionLimits()
    check(defaults.evaluationTimeoutMs > 0 && defaults.heapBytes > 0 && defaults.inputBytes > 0 && defaults.transferBytes > 0) { "default sandbox bounds must be positive" }
    check(defaults.providerTimeoutMs > 0 && defaults.discoveryTimeoutMs > 0) { "default provider and discovery deadlines must be positive" }
    // A host that configures nothing keeps the deadline the adapters used to hard-code: 5000 ms.
    check(defaults.providerTimeoutMs == 5_000L && defaults.discoveryTimeoutMs == 5_000L) { "an unconfigured host must keep the 5000 ms provider and discovery deadline" }

    // A provider call and a disclosure-refresh binding take the host's deadline, so a changed bound
    // moves the admitted terminal instant and no adapter decides it.
    val configured = ExecutionLimits(providerTimeoutMs = 250, discoveryTimeoutMs = 90)
    check(configured.providerDeadline(1_000L) == 1_250L) { "the provider deadline must start at admission and use the configured bound" }
    check(configured.discoveryDeadline(1_000L) == 1_090L) { "the discovery deadline must start at the refresh and use the configured bound" }
    check(ExecutionLimits(providerTimeoutMs = 60_000).providerDeadline(0L) == 60_000L) { "a longer configured provider deadline must be honoured" }
    check(defaults.providerDeadline(7L) == 7L + 5_000L) { "the default provider deadline must be admission plus the default bound" }

    // Every bound of the block is host configuration, and an unusable value never reaches work.
    rejects("a zero provider deadline") { ExecutionLimits(providerTimeoutMs = 0) }
    rejects("a negative provider deadline") { ExecutionLimits(providerTimeoutMs = -1) }
    rejects("a zero discovery deadline") { ExecutionLimits(discoveryTimeoutMs = 0) }
    rejects("a negative discovery deadline") { ExecutionLimits(discoveryTimeoutMs = -5) }
    rejects("a zero evaluation bound") { ExecutionLimits(evaluationTimeoutMs = 0) }
    rejects("a negative heap bound") { ExecutionLimits(heapBytes = -1) }
    rejects("a zero input bound") { ExecutionLimits(inputBytes = 0) }
    rejects("a negative transfer bound") { ExecutionLimits(transferBytes = -4096) }

    // A public probe declares its own finite bounds: the response byte bound, the document depth bound, the
    // selected-character bound, the selected-path bound and one wall-clock deadline per read.
    check(defaults.probeBytes > 0 && defaults.probeDepth > 0) { "default probe size bounds must be positive" }
    check(defaults.probeCharacters > 0 && defaults.probePaths > 0 && defaults.probeTimeoutMs > 0) { "default probe selection bounds and deadline must be positive" }
    rejects("a zero probe byte bound") { ExecutionLimits(probeBytes = 0) }
    rejects("a negative probe depth bound") { ExecutionLimits(probeDepth = -1) }
    rejects("a zero probe character bound") { ExecutionLimits(probeCharacters = 0) }
    rejects("a zero probe path bound") { ExecutionLimits(probePaths = 0) }
    rejects("a zero probe deadline") { ExecutionLimits(probeTimeoutMs = 0) }
    println("resource bounds: provider, discovery, evaluation, heap, input, transfer and public-probe bounds are host configuration and a nonpositive value is rejected before work")
}
