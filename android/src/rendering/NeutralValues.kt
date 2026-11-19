package dev.deal.embedding

/** Neutral structured values used by the pure renderer rules: no Android, Compose or org.json. */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Flag(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal object NeutralJson {
    /** Strict recursive-descent parse; null means the text is not validated JSON. */
    fun parse(text: String): JsonValue? {
        val reader = Reader(text)
        val value = runCatching { reader.value() }.getOrNull() ?: return null
        reader.skipSpace()
        return if (reader.done()) value else null
    }

    fun quote(text: String): String = buildString {
        append('"')
        for (character in text) when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
        append('"')
    }

    fun render(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> value.fields.entries.joinToString(",", "{", "}") { (name, item) -> quote(name) + ":" + render(item) }
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { render(it) }
        is JsonValue.Str -> quote(value.value)
        is JsonValue.Num -> numeric(value.value)
        is JsonValue.Flag -> if (value.value) "true" else "false"
        JsonValue.Null -> "null"
    }

    private fun numeric(value: Double): String =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private class Reader(private val text: String) {
        private var index = 0
        fun done() = index >= text.length
        fun skipSpace() { while (index < text.length && text[index].isWhitespace()) index++ }
        fun value(): JsonValue {
            skipSpace()
            require(index < text.length) { "unexpected end" }
            return when (val character = text[index]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Flag(true))
                'f' -> literal("false", JsonValue.Flag(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (character == '-' || character.isDigit()) number() else error("unexpected $character")
            }
        }
        private fun literal(spelling: String, value: JsonValue): JsonValue {
            require(text.startsWith(spelling, index)) { "expected $spelling" }
            index += spelling.length
            return value
        }
        private fun obj(): JsonValue {
            index++
            val fields = linkedMapOf<String, JsonValue>()
            skipSpace()
            if (index < text.length && text[index] == '}') { index++; return JsonValue.Obj(fields) }
            while (true) {
                skipSpace()
                val name = string()
                skipSpace()
                require(index < text.length && text[index] == ':') { "expected colon" }
                index++
                fields[name] = value()
                skipSpace()
                require(index < text.length) { "unexpected end" }
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.Obj(fields) }
                    else -> error("expected , or }")
                }
            }
        }
        private fun arr(): JsonValue {
            index++
            val items = mutableListOf<JsonValue>()
            skipSpace()
            if (index < text.length && text[index] == ']') { index++; return JsonValue.Arr(items) }
            while (true) {
                items.add(value())
                skipSpace()
                require(index < text.length) { "unexpected end" }
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> error("expected , or ]")
                }
            }
        }
        private fun string(): String {
            require(text[index] == '"') { "expected string" }
            index++
            val builder = StringBuilder()
            while (true) {
                require(index < text.length) { "unterminated string" }
                val character = text[index++]
                when (character) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < text.length) { "unterminated escape" }
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "short unicode escape" }
                                builder.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("unsupported escape $escape")
                        }
                    }
                    else -> builder.append(character)
                }
            }
        }
        private fun number(): JsonValue {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') { index++; while (index < text.length && text[index].isDigit()) index++ }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val value = text.substring(start, index).toDouble()
            require(value.isFinite()) { "nonfinite literal" }
            return JsonValue.Num(value)
        }
    }
}

/** One resolved prop value. Absence is the absence of the map entry, never a sentinel. */
internal sealed interface UiValue {
    data class Text(val value: String) : UiValue
    data class Num(val value: Double) : UiValue
    data class Flag(val value: Boolean) : UiValue
    data class Json(val value: JsonValue) : UiValue
    data class Slot(val value: Int) : UiValue
}

internal data class UiProp(val name: String, val value: UiValue)

/** One committed node as the renderer rules see it: component name without its module qualifier. */
internal data class UiNode(val component: String, val props: List<UiProp>, val children: List<UiNode>, val key: String = "") {
    fun prop(name: String): UiValue? = props.firstOrNull { it.name == name }?.value
}
