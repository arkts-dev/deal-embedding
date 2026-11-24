package dev.deal.embedding

/** Input hints change offered keys, never the typed string. */
internal enum class KeyboardHint { TEXT, NUMBER, DECIMAL, EMAIL, PHONE, URI, PASSWORD }
internal data class TextFieldFeedback(val rule: String, val message: String)

/** Display-only feedback. Pattern verdicts come from the bounded ECMAScript mechanism. */
internal object TextFieldRules {
    fun keyboard(name: String): KeyboardHint = when (name) {
        "number", "numeric" -> KeyboardHint.NUMBER
        "decimal" -> KeyboardHint.DECIMAL
        "email" -> KeyboardHint.EMAIL
        "phone" -> KeyboardHint.PHONE
        "uri", "url" -> KeyboardHint.URI
        "password" -> KeyboardHint.PASSWORD
        else -> KeyboardHint.TEXT
    }

    fun email(value: String): Boolean {
        if (value.isEmpty()) return false
        for (character in value) if (character.isWhitespace() || Character.isSpaceChar(character)) return false
        val at = value.indexOf('@')
        if (at <= 0 || value.indexOf('@', at + 1) >= 0) return false
        val domain = value.substring(at + 1)
        return domain.isNotEmpty() && domain.contains('.')
    }

    /** Preserve canonical rule order, optional-empty semantics, messages and UTF-16 lengths.
     * A null pattern verdict means evaluation is pending; it never suppresses an edit. */
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
        patternMatches: Boolean? = null,
    ): TextFieldFeedback? {
        if (value.isEmpty()) return if (required) TextFieldFeedback("required", requiredMessage) else null
        if (email && !this.email(value)) return TextFieldFeedback("email", emailMessage)
        val units = value.length.toDouble()
        if (minLength > 0 && units < minLength) return TextFieldFeedback("minLength", minLengthMessage)
        if (maxLength > 0 && units > maxLength) return TextFieldFeedback("maxLength", maxLengthMessage)
        if (pattern.isNotEmpty() && patternMatches == false) return TextFieldFeedback("pattern", patternMessage)
        return null
    }
}
