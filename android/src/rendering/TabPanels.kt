package dev.deal.embedding

/** The one panel a Tabs selection renders, with the identity that retains its keyed state. */
internal data class TabPanel(val index: Int, val identity: String, val node: UiNode)

/**
 * Tabs panel rule over neutral values.
 *
 * Tabs require one label per child and an integral index-valid selection, so exactly one child is
 * the rendered panel and the tab row carries one tab per child. A panel's retention identity is the
 * view identity the child carries, never the selection, so the saveable state holder the painter
 * uses keeps one panel's state per identity across a switch.
 *
 * An invalid tree yields no panel; ingress rejects it before painting, so this rule never renders
 * a neighbour in place of the selected child.
 */
internal object TabPanels {
    fun select(labels: List<String>, selected: Double, children: List<UiNode>): TabPanel? {
        if (labels.isEmpty() || labels.size != children.size) return null
        if (!selected.isFinite() || selected != kotlin.math.floor(selected)) return null
        val index = selected.toInt()
        if (index < 0 || index >= children.size) return null
        val child = children[index]
        return TabPanel(index, identities(children)[index], child)
    }

    /**
     * One retention identity per child: the node identity the child carries (its structural path and
     * declared key, as the transport composes them; a child that carries no key falls back to its
     * component name), length-prefixed so the duplicate form below can never collide with a carried
     * identity. Two panels therefore never share state. A child whose carried identity is
     * position-independent (a keyed row, or any view the emitter keys without its position) keeps its
     * state when its position changes; siblings that carry one identity additionally take their panel
     * position, so the state never lands on a sibling.
     */
    fun identities(children: List<UiNode>): List<String> {
        val names = children.map { it.key.ifEmpty { it.component } }
        return names.mapIndexed { index, name ->
            val identity = name.length.toString() + ":" + name
            if (names.count { it == name } > 1) identity + "#" + index else identity
        }
    }
}
