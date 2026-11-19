package dev.deal.embedding

/** One input reconstructed as the declared payload, or nothing for an undeclared/unreachable event. */
internal sealed interface EventPayload {
    /** The transport spelling the session decodes into the declared nominal payload; null is no payload. */
    data class Delivered(val spelling: String?) : EventPayload
    data class Rejected(val component: String, val event: String, val detail: String) : EventPayload
    /** The pack does not declare the event for this component, or the card profile forbids it. */
    data object Undeclared : EventPayload
}

internal object EventPayloads {
    fun kind(component: String, event: String): String? = RendererEvents.payloads[component]?.get(event)

    /**
     * Reconstructs exactly the declared payload type: never a callback name and never a
     * string spelling accepted as a boolean or a number.
     */
    fun reconstruct(component: String, event: String, values: Map<String, UiValue>, raw: String?): EventPayload {
        return when (kind(component, event) ?: return EventPayload.Undeclared) {
            "none" -> if (raw == null) EventPayload.Delivered(null) else EventPayload.Rejected(component, event, "declared event takes no payload")
            "string" -> if (raw == null) EventPayload.Rejected(component, event, "missing string payload") else EventPayload.Delivered(raw)
            "boolean" -> when (raw) {
                "true", "false" -> EventPayload.Delivered(raw)
                else -> EventPayload.Rejected(component, event, "boolean payload required")
            }
            "number" -> {
                val delivered = when (event) {
                    "onClick" -> number((values["value"] as? UiValue.Num)?.value)
                    "onTap" -> number(((values["eventValue"] ?: values["value"]) as? UiValue.Num)?.value)
                    "onFrame" -> number(raw?.toDoubleOrNull())?.takeIf { it.toDouble() >= 0 }
                    else -> number(raw?.toDoubleOrNull())
                }
                if (delivered == null) EventPayload.Rejected(component, event, "finite nonnegative number payload required")
                else EventPayload.Delivered(delivered)
            }
            "pointer" -> {
                val parsed = raw?.let(NeutralJson::parse)
                val failure = GraphicsValidation.pointer(parsed)
                if (failure != null || parsed == null) EventPayload.Rejected(component, event, failure ?: "pointer required")
                else EventPayload.Delivered(NeutralJson.render(parsed))
            }
            else -> EventPayload.Rejected(component, event, "payload type cannot be reconstructed")
        }
    }

    private fun number(value: Double?): String? {
        if (value == null || !value.isFinite()) return null
        val spelling = if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        return spelling
    }
}
