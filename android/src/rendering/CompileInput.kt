package dev.deal.embedding

/** The compile input's frozen target. Carried, never chosen or promoted by this domain. */
internal object CompileInput {
    val targets = setOf("screen", "chat", "card")

    /** Null when the compile input carries no target or a value outside the target set. */
    fun target(value: String?): String? = if (value in targets) value else null
}
