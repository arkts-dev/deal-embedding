package dev.deal.embedding

/**
 * Assembly of the issued contract script that a compiled Session receives before it mounts. Pure text:
 * the projections are already JSON, so only the target needs quoting and only the two line separators
 * are not valid inside a JavaScript string literal.
 */
internal object RendererContractScript {
    /** An issued projection is missing from the package: an authority verdict, never source feedback. */
    fun missing(path: String): Nothing =
        throw ProtocolFailure("ADMISSION_PACK", "issued renderer projection is missing: $path")

    /** An issued projection is not the JSON the generator wrote. */
    fun unparseable(path: String): Nothing =
        throw ProtocolFailure("ADMISSION_PACK", "issued renderer projection is not JSON: $path")

    /**
     * One contract script for a Session. The issued object is frozen and its global binding is made
     * read-only before any compiled module runs, so generated source cannot replace the facts or the
     * target the host issued; the renderer's ingress validator remains the paint-time backstop.
     */
    fun assemble(target: String, constraints: String, card: String, declarations: String): String = buildString {
        append("globalThis.rendererContract={target:").append(quote(target))
        append(",constraints:").append(literal(constraints))
        append(",card:").append(literal(card))
        append(",declarations:").append(literal(declarations))
        append("};")
        append("(() => { const issued = globalThis.rendererContract;")
        append(" const deep = value => { if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {")
        append(" Object.freeze(value); for (const name of Object.keys(value)) deep(value[name]); } };")
        append(" deep(issued);")
        append(" Object.defineProperty(globalThis, 'rendererContract', {value: issued, writable: false, configurable: false});")
        append(" })();")
    }

    /**
     * One JavaScript string literal for the target. The projections carry no such characters, so only
     * U+2028/U+2029 need escaping there; a target is host configuration and may carry anything.
     */
    private fun quote(text: String): String = buildString {
        append('"')
        for (character in text) when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(escape(character))
        }
        append('"')
    }

    private fun literal(json: String): String = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun escape(character: Char): String =
        if (character == '\u2028' || character == '\u2029' || character.code < 0x20) "\\u" + character.code.toString(16).padStart(4, '0')
        else character.toString()
}
