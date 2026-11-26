package dev.deal.embedding.capabilities

import dev.deal.embedding.ExecutionLimits
import dev.deal.embedding.ProtocolFailure

/**
 * The executed matrix of the one typed provider failure of spec v0.8 "Execution and provider workflows" and
 * "Failure and operations": a provider's own code and message survive the local and the Binder leg inside the
 * configured width, and only an external mutation whose transport failed is unknown.
 *
 * The transport that publishes these failures is an Android Service and a Binder callback, so its call sites
 * are asserted from production source by `tests/tools/test-provider-failures.py`; the decision executed here is
 * the one both of those call sites take.
 */
private var checked = 0
private fun need(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
    checked += 1
}
private fun rejects(block: () -> Unit, message: String) {
    if (runCatching { block() }.isFailure) checked += 1 else throw AssertionError(message)
}

fun main() {
    val defaults = ExecutionLimits()
    need(defaults.failureCodeChars > 0 && defaults.failureMessageChars > 0) { "An unconfigured host must bound a published failure" }
    val bounds = defaults.failureBounds()

    // The provider's own typed failure keeps exactly its code and message: the transport never substitutes a
    // generic code for a custom one, and a mutating failure the provider answered is still definite.
    val custom = ProviderFailure.answered(ProtocolFailure("UNKNOWN_TASK", "Unknown task"), bounds)
    need(custom.code == "UNKNOWN_TASK") { "A provider's custom code was rewritten: " + custom.code }
    need(custom.message == "Unknown task") { "A provider's message was rewritten: " + custom.message }
    need(custom.outcome == ProviderFailure.FAILURE) { "A provider's answer was reported uncertain: " + custom.outcome }
    need(custom.json().keys().asSequence().toSet() == setOf("code", "message", "outcome")) { "A failure reply carries fields the Session does not read" }
    need(custom.json().getString("code") == "UNKNOWN_TASK" && custom.json().getString("message") == "Unknown task" && custom.json().getString("outcome") == "failure") {
        "The published failure lost its typed code, message or outcome: " + custom.json()
    }

    // An untyped provider fault still states the provider's own reason, under the one stable fallback code.
    val untyped = ProviderFailure.answered(IllegalArgumentException("Unknown task"), bounds)
    need(untyped.code == FailureBounds.CODE) { "An untyped provider fault invented a code: " + untyped.code }
    need(untyped.message == "Unknown task") { "An untyped provider fault lost its message: " + untyped.message }
    need(untyped.outcome == ProviderFailure.FAILURE) { "An untyped provider fault was reported uncertain" }
    need(ProviderFailure.answered(IllegalStateException(), bounds).message == "Provider operation failed") { "A provider fault without a message must publish a located fallback" }
    need(ProviderFailure.answered(ProtocolFailure("", "No code"), bounds).code == FailureBounds.CODE) { "A blank provider code must fall back instead of publishing an empty code" }

    // A refusal of this host is the refusal's own code and reason, and it is definite: nothing left the lane.
    val refused = ProviderFailure.refused("DENIED", "Mutation requires host confirmation", bounds)
    need(refused.code == "DENIED" && refused.message == "Mutation requires host confirmation") { "A refusal lost its code or reason" }
    need(refused.outcome == ProviderFailure.FAILURE) { "A refusal before the call left was reported uncertain" }

    // Only an external mutation whose transport failed is unknown: the call may already have been applied, so it
    // is never replayed, while a read that lost its transport mutated nothing outside.
    val lostMutation = ProviderFailure.lost("TIMEOUT", "Capability call timed out", true, bounds)
    need(lostMutation.outcome == ProviderFailure.UNKNOWN) { "A mutating call that lost its transport was reported definite: " + lostMutation.outcome }
    need(lostMutation.code == "TIMEOUT" && lostMutation.message == "Capability call timed out") { "A lost call lost its code or reason" }
    need(lostMutation.json().getString("outcome") == "unknown") { "The published outcome did not carry the uncertainty" }
    need(ProviderFailure.lost("PROVIDER_DIED", "Provider unavailable", false, bounds).outcome == ProviderFailure.FAILURE) { "A read that lost its transport was reported uncertain" }

    // The configured bound is where the width of a published failure comes from: a narrower host bound shortens
    // the code and the message without replacing either of them, and a predicate never widens one.
    val narrow = ExecutionLimits(failureCodeChars = 8, failureMessageChars = 12).failureBounds()
    val longCode = "CUSTOM_" + "X".repeat(200)
    val longMessage = "The provider refused: " + "y".repeat(5000)
    val bounded = ProviderFailure.answered(ProtocolFailure(longCode, longMessage), narrow)
    need(bounded.code == longCode.take(8)) { "The configured code bound was not applied: " + bounded.code }
    need(bounded.message == longMessage.take(12)) { "The configured message bound was not applied: " + bounded.message }
    need(ProviderFailure.answered(ProtocolFailure(longCode, longMessage), bounds).message.length == defaults.failureMessageChars) {
        "The default message bound was not applied"
    }
    need(ProviderFailure.answered(ProtocolFailure(longCode, longMessage), bounds).code == longCode.take(defaults.failureCodeChars)) {
        "The default code bound was not applied"
    }
    // The bound is measured in UTF-16 units like every other interchange bound here, so a surrogate pair is
    // shortened by units and never by code points.
    val astral = "\uD83D\uDE00".repeat(300)
    need(ProviderFailure.answered(IllegalStateException(astral), narrow).message.length == 12) { "The message bound did not count UTF-16 units" }
    // A message inside the bound is untouched, whitespace included: strings stay data.
    need(ProviderFailure.answered(IllegalStateException("  spared\r\n"), bounds).message == "  spared\r\n") { "A message inside the bound was rewritten" }

    // A failure can never be published with an unknown classification or a nonpositive bound.
    rejects({ ProviderFailure("X", "y", "pending") }, "A failure accepted an outcome that is neither definite nor unknown")
    rejects({ ExecutionLimits(failureCodeChars = 0) }, "A zero failure-code bound was accepted")
    rejects({ ExecutionLimits(failureMessageChars = -1) }, "A negative failure-message bound was accepted")
    rejects({ FailureBounds(codeChars = 0) }, "A failure bound of zero characters was accepted")

    println("provider failures: $checked checks - a provider's typed code and message survive both legs inside the configured bound, and only an external mutation that lost its transport is unknown")
}
