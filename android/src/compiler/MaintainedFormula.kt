package dev.deal.embedding

import org.json.JSONTokener

/** Pure configuration expressions, never DEAL/JS source. Adapted from OHOS parseFormula:
 * parse first, resolve every name, then lower through the existing typed operation emitter.
 * Provider calls, mutation, declarations and arbitrary function calls are not in this grammar. */
internal object MaintainedFormula {
    sealed interface Expression {
        data class Literal(val value: Any) : Expression
        data class Reference(val path: String) : Expression
        data class Operation(val name: String, val arguments: List<Expression>) : Expression
    }
    private data class Token(val text: String, val offset: Int, val kind: Kind)
    private enum class Kind { NAME, NUMBER, STRING, SYMBOL, END }
    private val functions = mapOf(
        "floor" to 1, "ceil" to 1, "abs" to 1, "choose" to 3,
        "clock" to 1, "integer" to 1, "length" to 1,
        "sum" to 2, "filter" to 2, "any" to 2, "all" to 2, "project" to 2,
    )
    private val operators = mapOf(
        "||" to (1 to "or"), "&&" to (2 to "and"),
        "===" to (3 to "eq"), "!==" to (3 to "ne"),
        "<" to (4 to "lt"), "<=" to (4 to "le"), ">" to (4 to "gt"), ">=" to (4 to "ge"),
        "+" to (5 to "add"), "-" to (5 to "subtract"), "*" to (6 to "multiply"), "/" to (6 to "divide"),
    )
    private fun reject(message: String): Nothing = throw BehaviorCandidate.Rejected("Formula: $message")

    fun parse(source: String): Expression = Parser(source).parse()

    /** Includes names in nested collection predicates; the fragment binder owns lexical row scope. */
    fun references(expression: Expression): Set<String> = when (expression) {
        is Expression.Literal -> emptySet()
        is Expression.Reference -> setOf(expression.path)
        is Expression.Operation -> expression.arguments.flatMap { references(it) }.toSet()
    }

    /** Resolve all names before adding any nodes. A failed binding cannot partially lower a formula. */
    fun lower(expression: Expression, reference: (String) -> String,
              literal: (Any) -> String, operation: (String, List<String>) -> String): String {
        val resolved = references(expression).associateWith(reference)
        fun emit(value: Expression): String = when (value) {
            is Expression.Literal -> literal(value.value)
            is Expression.Reference -> resolved.getValue(value.path)
            is Expression.Operation -> operation(value.name, value.arguments.map(::emit))
        }
        return emit(expression)
    }

    private class Parser(private val source: String) {
        private var offset = 0
        private var token = next()
        private fun fail(message: String): Nothing = reject("at character ${token.offset + 1}: $message")
        private fun advance(): Token { val previous = token; token = next(); return previous }
        private fun consume(text: String) { if (token.text != text) fail("expected '$text'"); advance() }
        fun parse(): Expression {
            val result = binary(1)
            if (token.kind != Kind.END) fail("unexpected '${token.text}'")
            return result
        }
        private fun binary(minimum: Int): Expression {
            var left = unary()
            while (true) {
                val (precedence, name) = operators[token.text] ?: break
                if (precedence < minimum) break
                advance()
                left = Expression.Operation(name, listOf(left, binary(precedence + 1)))
            }
            return left
        }
        private fun unary(): Expression = when (token.text) {
            "!" -> { advance(); Expression.Operation("not", listOf(unary())) }
            "-" -> { advance(); Expression.Operation("subtract", listOf(Expression.Literal(0), unary())) }
            "+" -> { advance(); Expression.Operation("add", listOf(Expression.Literal(0), unary())) }
            else -> primary()
        }
        private fun primary(): Expression {
            if (token.text == "(") { advance(); val value = binary(1); consume(")"); return value }
            val current = token
            when (current.kind) {
                Kind.NUMBER -> {
                    advance()
                    val number = current.text.toDoubleOrNull() ?: fail("invalid number")
                    if (!number.isFinite()) fail("nonfinite numeric literal")
                    return Expression.Literal(number)
                }
                Kind.STRING -> { advance(); return Expression.Literal(JSONTokener(current.text).nextValue() as String) }
                Kind.NAME -> {
                    advance()
                    if (current.text in setOf("true", "false")) return Expression.Literal(current.text == "true")
                    if (token.text != "(") return Expression.Reference(current.text)
                    if (current.text != "concat" && current.text !in functions) fail("unsupported function '${current.text}'")
                    advance()
                    val arguments = mutableListOf<Expression>()
                    if (token.text != ")") {
                        arguments.add(binary(1))
                        while (token.text == ",") { advance(); arguments.add(binary(1)) }
                    }
                    consume(")")
                    if (current.text != "concat" && arguments.size != functions.getValue(current.text))
                        fail("${current.text} expects ${functions.getValue(current.text)} arguments")
                    return Expression.Operation(current.text, arguments)
                }
                else -> fail("expected a literal, declared reference or pure expression")
            }
        }
        private fun next(): Token {
            while (offset < source.length && source[offset].isWhitespace()) offset++
            val start = offset
            if (offset == source.length) return Token("", offset, Kind.END)
            val ch = source[offset]
            if (ch == '"') {
                offset++
                while (offset < source.length) {
                    val c = source[offset++]
                    if (c == '"') return Token(source.substring(start, offset), start, Kind.STRING)
                    if (c.code < 32) reject("at character $offset: unescaped string control")
                    if (c == '\\') {
                        if (offset == source.length) break
                        val escaped = source[offset++]
                        if (escaped == 'u') {
                            repeat(4) {
                                if (offset == source.length || source[offset].digitToIntOrNull(16) == null)
                                    reject("at character ${offset + 1}: invalid Unicode escape")
                                offset++
                            }
                        } else if (escaped !in "\"\\/bfnrt") reject("at character $offset: invalid string escape")
                    }
                }
                reject("at character ${start + 1}: unterminated string")
            }
            if (ch in '0'..'9' || ch == '.' && offset + 1 < source.length && source[offset + 1] in '0'..'9') {
                val match = NUMBER.find(source, offset)
                if (match == null || match.range.first != offset) reject("at character ${offset + 1}: invalid number")
                offset = match.range.last + 1
                return Token(match.value, start, Kind.NUMBER)
            }
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch == '_') {
                offset++
                while (offset < source.length && (source[offset].isLetterOrDigit() && source[offset].code < 128 || source[offset] == '_')) offset++
                while (offset < source.length && source[offset] == '.') {
                    offset++
                    if (offset == source.length || !(source[offset] in 'A'..'Z' || source[offset] in 'a'..'z' || source[offset] == '_'))
                        reject("at character ${offset + 1}: field name required")
                    offset++
                    while (offset < source.length && (source[offset].isLetterOrDigit() && source[offset].code < 128 || source[offset] == '_')) offset++
                }
                return Token(source.substring(start, offset), start, Kind.NAME)
            }
            val symbol = listOf("===", "!==", "<=", ">=", "&&", "||", "+", "-", "*", "/", "!", "<", ">", "(", ")", ",")
                .firstOrNull { source.startsWith(it, offset) } ?: reject("at character ${offset + 1}: forbidden token '$ch'")
            offset += symbol.length
            return Token(symbol, start, Kind.SYMBOL)
        }
    }
    private val NUMBER = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
}
