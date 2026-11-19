package dev.deal.embedding

/** Copy-only presentation transform over the shipped display vocabulary.
 *
 * Only the displayable string props of spec components are rewritten; numbers, payloads, action
 * slots, keys, inputs and every component outside the table are copied unchanged. Nodes are
 * immutable values, so the caller's tree is never mutated.
 */
internal fun displayText(tree: UiNode, transform: (String) -> String): UiNode {
    val displayable = DisplayVocabulary.table[tree.component] ?: emptySet()
    return tree.copy(
        props = tree.props.map { prop ->
            val value = prop.value
            if (prop.name in displayable && value is UiValue.Text) UiProp(prop.name, UiValue.Text(transform(value.value))) else prop
        },
        children = tree.children.map { displayText(it, transform) },
    )
}
