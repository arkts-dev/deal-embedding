package dev.deal.embedding

/** One leaf's in-progress text and the authoritative value identity it was typed against. */
internal data class Draft(val text: String, val origin: String)

/**
 * Draft presentation rule.
 *
 * A retentive draft outlives the composition that typed it, so it applies only while the
 * authoritative value identity it was typed against is unchanged. Once that identity changes, the
 * draft is replaced by the authoritative presentation, so the panel never revives input typed
 * against a superseded value.
 */
internal object DraftText {
    /**
     * Identity of a numeric authoritative value: its shortest round-tripping spelling, never the
     * rounded rendering, so two values that render the same text still keep separate drafts.
     */
    fun identity(value: Double): String = NumericPresentation.format(value, "", "", 1, -1)

    fun reconciled(draft: Draft, identity: String, authoritative: String): Draft =
        if (draft.origin == identity) draft else Draft(authoritative, identity)

    fun shown(draft: Draft, identity: String, authoritative: String): String =
        reconciled(draft, identity, authoritative).text
}
