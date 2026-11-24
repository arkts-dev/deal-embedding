package dev.deal.embedding

import java.util.regex.Pattern

/** A declared TextField input hint. It changes the keys the platform offers, never what is typed. */
internal enum class KeyboardHint { TEXT, NUMBER, DECIMAL, EMAIL, PHONE, URI, PASSWORD }

/** The first violated declaration with the message the tree carries for it. */
internal data class TextFieldFeedback(val rule: String, val message: String)

/**
 * The declared TextField validation and feedback rules.
 *
 * Feedback never replaces DEAL validation and never suppresses input: the painter always keeps the
 * typed draft and always dispatches the typed string, and this module only says which declared rule
 * the current text violates and which message to show. Lengths are UTF-16 units, so an astral
 * character counts as the two units a JavaScript `length` counts, never split.
 *
 * The declared pattern is an ECMAScript Unicode pattern matched against the whole value, and the
 * native mechanism is the platform's regex engine. [portable] therefore admits only the subset both
 * engines spell and mean identically: it rewrites `\u{...}` code-point escapes, `[]`, `[^]`, `\s`,
 * `\S`, `\v`, `.`, `$` and the Unicode property names into the platform's spelling, and refuses
 * anything else - a construct ECMAScript refuses, or one the platform cannot mean the same way - so
 * an admitted pattern means the same thing here as in the declaration, and a pattern outside the
 * subset faults at ingress instead of being reinterpreted. A Unicode category, script or binary
 * property is one difference no rewriting removes: its verdict is the platform's own Unicode tables,
 * which may classify a code point differently from a newer engine's tables.
 */
internal object TextFieldRules {
    /**
     * `keyboardType` is a key hint only. An absent or unrecognized name asks for the ordinary text
     * keys rather than faulting, because the declared type is an open string.
     */
    fun keyboard(name: String): KeyboardHint = when (name) {
        "number", "numeric" -> KeyboardHint.NUMBER
        "decimal" -> KeyboardHint.DECIMAL
        "email" -> KeyboardHint.EMAIL
        "phone" -> KeyboardHint.PHONE
        "uri", "url" -> KeyboardHint.URI
        "password" -> KeyboardHint.PASSWORD
        else -> KeyboardHint.TEXT
    }

    /** An empty pattern declares no check; an invalid one is an ingress fault, never a silent pass. */
    fun validPattern(pattern: String): Boolean = pattern.isEmpty() || compiled(pattern) != null

    /** Full-string match of the declared pattern; an absent or invalid pattern matches nothing. */
    fun matches(pattern: String, value: String): Boolean {
        if (pattern.isEmpty()) return false
        return compiled(pattern)?.matcher(value)?.matches() ?: false
    }

    /**
     * One nonempty local part, one `@` and a nonempty dotted domain, without whitespace.
     */
    fun email(value: String): Boolean {
        if (value.isEmpty()) return false
        for (character in value) if (character.isWhitespace() || Character.isSpaceChar(character)) return false
        val at = value.indexOf('@')
        if (at <= 0) return false
        if (value.indexOf('@', at + 1) >= 0) return false
        val domain = value.substring(at + 1)
        return domain.isNotEmpty() && domain.contains('.')
    }

    /**
     * The first violated rule in declaration order: required, email, minLength, maxLength, pattern.
     * A rule other than `required` applies only to a nonempty value, so an untouched optional field
     * does not report a length or pattern complaint. A disabled rule (false, 0, empty) never fires.
     */
    fun feedback(
        value: String,
        required: Boolean = false,
        email: Boolean = false,
        minLength: Double = 0.0,
        maxLength: Double = 0.0,
        pattern: String = "",
        requiredMessage: String = "",
        emailMessage: String = "",
        minLengthMessage: String = "",
        maxLengthMessage: String = "",
        patternMessage: String = "",
    ): TextFieldFeedback? {
        if (value.isEmpty()) return if (required) TextFieldFeedback("required", requiredMessage) else null
        if (email && !this.email(value)) return TextFieldFeedback("email", emailMessage)
        val units = value.length.toDouble()
        if (minLength > 0 && units < minLength) return TextFieldFeedback("minLength", minLengthMessage)
        if (maxLength > 0 && units > maxLength) return TextFieldFeedback("maxLength", maxLengthMessage)
        if (pattern.isNotEmpty() && !matches(pattern, value)) return TextFieldFeedback("pattern", patternMessage)
        return null
    }

    /** The characters ECMAScript's `\s` matches, in the platform engine's class spelling. */
    private const val WHITESPACE = "\\u0009\\u000A\\u000B\\u000C\\u000D\\u0020\\u00A0\\u1680" +
        "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** The syntax characters ECMAScript lets a pattern escape, plus `/`, which needs no escape. */
    private const val SYNTAX = "^$\\.*+?()[]{}|/"

    /** Long Unicode category names the platform only knows by their short alias. */
    private val CATEGORIES = mapOf(
        "Letter" to "L", "Cased_Letter" to "LC", "Lowercase_Letter" to "Ll", "Uppercase_Letter" to "Lu",
        "Titlecase_Letter" to "Lt", "Modifier_Letter" to "Lm", "Other_Letter" to "Lo",
        "Mark" to "M", "Nonspacing_Mark" to "Mn", "Spacing_Mark" to "Mc", "Enclosing_Mark" to "Me",
        "Number" to "N", "Decimal_Number" to "Nd", "Letter_Number" to "Nl", "Other_Number" to "No",
        "Punctuation" to "P", "Connector_Punctuation" to "Pc", "Dash_Punctuation" to "Pd",
        "Open_Punctuation" to "Ps", "Close_Punctuation" to "Pe", "Initial_Punctuation" to "Pi",
        "Final_Punctuation" to "Pf", "Other_Punctuation" to "Po",
        "Symbol" to "S", "Math_Symbol" to "Sm", "Currency_Symbol" to "Sc", "Modifier_Symbol" to "Sk",
        "Other_Symbol" to "So",
        "Separator" to "Z", "Space_Separator" to "Zs", "Line_Separator" to "Zl", "Paragraph_Separator" to "Zp",
        "Other" to "C", "Control" to "Cc", "Format" to "Cf", "Surrogate" to "Cs", "Private_Use" to "Co",
        "Unassigned" to "Cn",
    )

    /**
     * The binary Unicode properties ECMAScript declares. The platform names each with an `Is`
     * prefix; one it does not implement fails to compile and is refused, never reinterpreted.
     */
    private val BINARY = setOf(
        "ASCII", "ASCII_Hex_Digit", "Alphabetic", "Assigned", "Bidi_Control", "Bidi_Mirrored",
        "Case_Ignorable", "Cased", "Changes_When_Casefolded", "Changes_When_Casemapped",
        "Changes_When_Lowercased", "Changes_When_NFKC_Casefolded", "Changes_When_Titlecased",
        "Changes_When_Uppercased", "Dash", "Default_Ignorable_Code_Point", "Deprecated", "Diacritic",
        "Emoji", "Emoji_Component", "Emoji_Modifier", "Emoji_Modifier_Base", "Emoji_Presentation",
        "Extended_Pictographic", "Extender", "Grapheme_Base", "Grapheme_Extend", "Hex_Digit",
        "IDS_Binary_Operator", "IDS_Trinary_Operator", "ID_Continue", "ID_Start", "Ideographic",
        "Join_Control", "Logical_Order_Exception", "Lowercase", "Math", "Noncharacter_Code_Point",
        "Pattern_Syntax", "Pattern_White_Space", "Quotation_Mark", "Radical", "Regional_Indicator",
        "Sentence_Terminal", "Soft_Dotted", "Terminal_Punctuation", "Unified_Ideograph", "Uppercase",
        "Variation_Selector", "White_Space", "XID_Continue", "XID_Start",
    )

    private const val CACHE_SIZE = 24

    /** Compiled patterns by declared spelling; a keystroke re-uses the same compiled pattern. */
    private val cache = object : LinkedHashMap<String, Pattern?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern?>?): Boolean = size > CACHE_SIZE
    }

    private fun compiled(pattern: String): Pattern? {
        synchronized(cache) { if (cache.containsKey(pattern)) return cache[pattern] }
        val translated = portable(pattern)
        val compiled = translated?.let { runCatching { Pattern.compile(it) }.getOrNull() }
        synchronized(cache) { cache[pattern] = compiled }
        return compiled
    }

    /** The state of the scan: what the previous token allows the next one to be. */
    private enum class Scan { START, ATOM, ASSERTION, QUANTIFIER, LAZY }

    private class Token(val text: String, val end: Int, val state: Scan, val classEscape: Boolean = false)

    /**
     * The platform spelling of a declared ECMAScript pattern, or null when it lies outside the
     * subset both engines spell and mean the same way.
     */
    fun portable(pattern: String): String? {
        val output = StringBuilder(pattern.length + 16)
        var state = Scan.START
        val groups = ArrayDeque<Boolean>()
        var inClass = false
        var classStart = false
        var classEscape = false
        var classDash = false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            if (inClass) {
                when {
                    classStart && character == '^' -> { output.append(character); index++ }
                    character == ']' -> { output.append(character); index++; inClass = false; state = Scan.ATOM }
                    character == '[' -> { output.append("\\x5B"); index++; classEscape = false; classDash = false }
                    character == '&' -> { output.append("\\x26"); index++; classEscape = false; classDash = false }
                    character == '-' -> {
                        // A class escape is one character, never a range endpoint; a dash that
                        // closes the class is a literal.
                        val literal = index + 1 < pattern.length && pattern[index + 1] == ']'
                        if (classEscape && !literal) return null
                        output.append(character); index++; classDash = true; classEscape = false
                    }
                    character == '\\' -> {
                        val escaped = escape(pattern, index, inClass = true) ?: return null
                        if (classDash && escaped.classEscape) return null
                        output.append(escaped.text); index = escaped.end
                        classEscape = escaped.classEscape; classDash = false
                    }
                    else -> { output.append(character); index++; classEscape = false; classDash = false }
                }
                if (character != '^' || !classStart) classStart = false
                continue
            }
            when {
                character == '\\' -> {
                    val escaped = escape(pattern, index, inClass = false) ?: return null
                    output.append(escaped.text); index = escaped.end; state = escaped.state
                }
                character == '[' -> {
                    if (pattern.startsWith("[^]", index)) { output.append("[\\s\\S]"); index += 3; state = Scan.ATOM }
                    else if (pattern.startsWith("[]", index)) { output.append("(?!)"); index += 2; state = Scan.ATOM }
                    else { output.append(character); index++; inClass = true; classStart = true; classEscape = false; classDash = false }
                }
                character == ']' || character == '}' -> return null
                character == '(' -> {
                    val token = group(pattern, index) ?: return null
                    output.append(token.text); index = token.end; groups.addLast(token.state == Scan.ASSERTION); state = token.state
                }
                character == ')' -> {
                    if (groups.isEmpty()) return null
                    // A lookaround closes as an assertion: it cannot be quantified.
                    state = if (groups.removeLast()) Scan.ASSERTION else Scan.ATOM
                    output.append(character); index++
                }
                character == '|' -> { output.append(character); index++; state = Scan.START }
                character == '^' -> { output.append(character); index++; state = Scan.ASSERTION }
                character == '$' -> { output.append("\\z"); index++; state = Scan.ASSERTION }
                character == '.' -> { output.append("[^\\n\\r\\u2028\\u2029]"); index++; state = Scan.ATOM }
                character == '*' || character == '+' || character == '?' || character == '{' -> {
                    when (state) {
                        Scan.ATOM -> {
                            if (character == '{') {
                                val token = quantifier(pattern, index) ?: return null
                                output.append(token.text); index = token.end
                            } else {
                                output.append(character); index++
                            }
                            state = Scan.QUANTIFIER
                        }
                        Scan.QUANTIFIER -> {
                            // A trailing `?` is the lazy form; anything else repeats a quantifier.
                            if (character != '?') return null
                            output.append(character); index++; state = Scan.LAZY
                        }
                        else -> return null
                    }
                }
                else -> { output.append(character); index++; state = Scan.ATOM }
            }
        }
        if (inClass || groups.isNotEmpty()) return null
        return output.toString()
    }

    /**
     * One group prefix: the platform's spelling of a non-capturing group, a lookahead or a capture.
     * A lookbehind is outside the shared set: a body that can match the empty string at more than one
     * length anchors differently in the two engines, so the declaration is refused instead.
     */
    private fun group(pattern: String, start: Int): Token? = when {
        pattern.startsWith("(?:", start) -> Token("(?:", start + 3, Scan.START)
        pattern.startsWith("(?=", start) -> Token("(?=", start + 3, Scan.ASSERTION)
        pattern.startsWith("(?!", start) -> Token("(?!", start + 3, Scan.ASSERTION)
        start + 1 < pattern.length && pattern[start + 1] == '?' -> null
        else -> Token("(", start + 1, Scan.START)
    }

    /** One `{n}`, `{n,}` or `{n,m}` repetition; any other brace is not an ECMAScript quantifier. */
    private fun quantifier(pattern: String, start: Int): Token? {
        var index = start + 1
        val digits = index
        while (index < pattern.length && pattern[index].isDigit()) index++
        if (index == digits) return null
        if (index < pattern.length && pattern[index] == ',') {
            index++
            while (index < pattern.length && pattern[index].isDigit()) index++
        }
        if (index >= pattern.length || pattern[index] != '}') return null
        return Token(pattern.substring(start, index + 1), index + 1, Scan.QUANTIFIER)
    }

    /** One escape, in the platform's spelling of the declared character or class. */
    private fun escape(pattern: String, start: Int, inClass: Boolean): Token? {
        if (start + 1 >= pattern.length) return null
        val character = pattern[start + 1]
        val alphabetic = character in 'a'..'z' || character in 'A'..'Z'
        if (!alphabetic) {
            if (character.isDigit()) {
                // Only `\0` is a character; `\1` and above are backreferences outside the subset.
                if (character != '0') return null
                if (start + 2 < pattern.length && pattern[start + 2].isDigit()) return null
                return Token("\\x00", start + 2, Scan.ATOM)
            }
            if (character in SYNTAX) return Token("\\" + character, start + 2, Scan.ATOM)
            if (inClass && character == '-') return Token("\\-", start + 2, Scan.ATOM)
            return null
        }
        return when (character) {
            'd', 'D', 'w', 'W' -> Token("\\" + character, start + 2, Scan.ATOM, classEscape = true)
            'n', 'r', 't', 'f' -> Token("\\" + character, start + 2, Scan.ATOM)
            'v' -> Token("\\x0B", start + 2, Scan.ATOM)
            'b', 'B' -> if (inClass) (if (character == 'b') Token("\\x08", start + 2, Scan.ATOM) else null)
                       else Token("\\" + character, start + 2, Scan.ASSERTION)
            's' -> Token(if (inClass) WHITESPACE else "[$WHITESPACE]", start + 2, Scan.ATOM, classEscape = true)
            'S' -> if (inClass) null else Token("[^$WHITESPACE]", start + 2, Scan.ATOM, classEscape = true)
            'x' -> hexEscape(pattern, start, 2, "\\x")
            'u' -> if (pattern.startsWith("{", start + 2)) codePointEscape(pattern, start)
                   else hexEscape(pattern, start, 4, "\\u")
            'p', 'P' -> propertyEscape(pattern, start, inClass)
            else -> null
        }
    }

    /** `\xHH` or `\uHHHH`: the platform spells these exactly as ECMAScript does. */
    private fun hexEscape(pattern: String, start: Int, digits: Int, prefix: String): Token? {
        val first = start + 2
        if (first + digits > pattern.length) return null
        for (offset in 0 until digits) if (pattern[first + offset] !in HEX) return null
        return Token(prefix + pattern.substring(first, first + digits), first + digits, Scan.ATOM)
    }

    /** `\u{...}`: the declared code point, in the platform's `\x{...}` spelling. */
    private fun codePointEscape(pattern: String, start: Int): Token? {
        val first = start + 3
        val close = pattern.indexOf('}', first)
        if (close < 0) return null
        val raw = pattern.substring(first, close)
        // ECMAScript requires at least one hexadecimal digit; leading zeros name the value.
        if (raw.isEmpty() || raw.any { it !in HEX }) return null
        val digits = raw.trimStart('0')
        if (digits.isEmpty()) return Token("\\x{0}", close + 1, Scan.ATOM)
        if (digits.length > 6) return null
        if (digits.toInt(16) > 0x10FFFF) return null
        return Token("\\x{$digits}", close + 1, Scan.ATOM)
    }

    /** `\p{...}` and `\P{...}`: a category, script or binary property the platform names the same way. */
    private fun propertyEscape(pattern: String, start: Int, inClass: Boolean): Token? {
        if (start + 2 >= pattern.length || pattern[start + 2] != '{') return null
        val close = pattern.indexOf('}', start + 3)
        if (close < 0) return null
        val name = pattern.substring(start + 3, close)
        if (name == "Any") {
            // Any code point: the platform's class for it, or nothing at all for its complement.
            if (pattern[start + 1] == 'P') {
                if (inClass) return null
                return Token("(?!)", close + 1, Scan.ATOM)
            }
            return Token(if (inClass) "\\s\\S" else "[\\s\\S]", close + 1, Scan.ATOM)
        }
        val property = property(name) ?: return null
        return Token("\\" + pattern[start + 1] + "{" + property + "}", close + 1, Scan.ATOM, classEscape = true)
    }

    /** One declared Unicode property in the platform's spelling, or null outside the shared set. */
    private fun property(name: String): String? {
        if (name.isEmpty()) return null
        val equal = name.indexOf('=')
        if (equal > 0) {
            val key = when (name.substring(0, equal)) {
                "General_Category", "gc" -> "General_Category"
                "Script", "sc" -> "Script"
                else -> return null
            }
            val value = name.substring(equal + 1)
            if (value.isEmpty()) return null
            return key + "=" + (CATEGORIES[value] ?: value)
        }
        if (name.length <= 2 && name.all { it.isLetter() }) return name
        CATEGORIES[name]?.let { return it }
        if (name in BINARY) return if (name == "ASCII") name else "Is$name"
        return null
    }

    private const val HEX = "0123456789abcdefABCDEF"
}
