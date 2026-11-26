package dev.deal.embedding.capabilities

import dev.deal.embedding.ExecutionLimits
import dev.deal.embedding.ProtocolFailure
import org.json.JSONObject

/**
 * The characters of one provider failure's code and message that a transport may publish. A failure is
 * never rewritten to fit: the provider's own code and message travel through the local and the Binder
 * leg and are shortened to these finite widths, so a bounded reply never needs a substitute code or a
 * substitute reason.
 */
internal class FailureBounds(val codeChars: Int = 64, val messageChars: Int = 200) {
    init { require(codeChars > 0 && messageChars > 0) { "Failure bounds must be positive" } }

    /** The code of one failure: the provider's own code, or the transport's stable fallback when it states none. */
    fun code(text: String): String = if (text.isBlank()) CODE else text.take(codeChars)

    /** The message of one failure, measured in UTF-16 units as every other interchange bound here is. */
    fun message(text: String): String = text.take(messageChars)

    companion object {
        /** The stable code of a provider fault that states no code of its own. */
        const val CODE = "PROVIDER_ERROR"

        /**
         * The declared width of one failure on a provider's own leg, before it crosses Binder. The
         * consuming host narrows the published code and message again to the bound it configured, so the
         * provider's leg never widens a host bound; it only keeps a failure inside one Binder transaction.
         */
        val TRANSPORT = FailureBounds()
    }
}

/** The width one host configured for a published failure: the transport reads it from the block, never from itself. */
internal fun ExecutionLimits.failureBounds(): FailureBounds = FailureBounds(failureCodeChars, failureMessageChars)

/**
 * One typed terminal failure of the capability transport: the provider's own code and message, and the
 * outcome the transport can decide for the call it answered.
 *
 * [FAILURE] is a definite outcome: a refusal before the call left the lane, or the provider's own answer,
 * changed nothing that could still happen. [UNKNOWN] is used only for an external mutation whose transport
 * failed after the call had left for the provider, because that mutation may already have been applied;
 * such a call is never replayed. A typed provider failure is therefore always definite: a provider that
 * answered decided its own call, exactly as [spec v0.8](docs/spec-v0.8.md) "Failure and operations" requires
 * ("preserve definite pre-send denial/validation failure as failure; only an uncertain external mutation
 * outcome is unknown").
 */
internal class ProviderFailure(val code: String, val message: String, val outcome: String) {
    init { require(outcome == FAILURE || outcome == UNKNOWN) { "Unknown failure outcome: $outcome" } }

    /** The error of one terminal reply: the code, the bounded message and the outcome, and nothing else. */
    fun json(): JSONObject = JSONObject().put("code", code).put("message", message).put("outcome", outcome)

    companion object {
        const val FAILURE = "failure"
        const val UNKNOWN = "unknown"

        /** The provider's own answer: a thrown [ProtocolFailure] keeps its code, any other fault keeps its message. */
        fun answered(error: Throwable, bounds: FailureBounds): ProviderFailure {
            val typed = error as? ProtocolFailure
            return ProviderFailure(bounds.code(typed?.code ?: ""), bounds.message(typed?.message ?: error.message ?: "Provider operation failed"), FAILURE)
        }

        /** A failure a provider reported over the transport: its code and message are the answer it decided. */
        fun answered(code: String, message: String, bounds: FailureBounds): ProviderFailure =
            ProviderFailure(bounds.code(code), bounds.message(message), FAILURE)

        /** A refusal of this host before the call left the lane: it changed nothing, so its outcome is definite. */
        fun refused(code: String, message: String, bounds: FailureBounds): ProviderFailure =
            ProviderFailure(bounds.code(code), bounds.message(message), FAILURE)

        /** A transport failure of a call that had already left the lane; only an external mutation is uncertain. */
        fun lost(code: String, message: String, mutating: Boolean, bounds: FailureBounds): ProviderFailure =
            ProviderFailure(bounds.code(code), bounds.message(message), if (mutating) UNKNOWN else FAILURE)
    }
}
