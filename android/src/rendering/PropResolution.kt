package dev.deal.embedding

import deal.ui.UiModel

/** Pack literal defaults as the neutral metadata value the renderer resolves. */
internal object PropDeclarationFacts {
    fun defaultOf(expression: UiModel.Expr?, type: UiModel.TypeRef): PropDefault? {
        if (expression == null) return null
        return when {
            type.array() -> PropDefault.Json(literalText(expression))
            type.name() == "string" -> PropDefault.Text(literalText(expression).let { text ->
                require(text.startsWith("\"")) { "expected a string default" }
                NeutralJson.parse(text)?.let { (it as? JsonValue.Str)?.value } ?: error("expected a string default")
            })
            type.name() == "number" -> PropDefault.Num(((expression as? UiModel.Literal)?.value() as? Number)?.toDouble()
                ?: error("expected a numeric default"))
            type.name() == "boolean" -> PropDefault.Flag((expression as? UiModel.Literal)?.value() as? Boolean
                ?: error("expected a boolean default"))
            else -> PropDefault.Json(literalText(expression))
        }
    }

    private fun numberText(value: Double): String =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun literalText(expression: UiModel.Expr): String {
        val literal = expression as? UiModel.Literal
            ?: throw IllegalStateException("$PACK_MISMATCH default: only literal defaults are expressible")
        return when (val value = literal.value()) {
            is String -> NeutralJson.quote(value)
            is Boolean -> if (value) "true" else "false"
            is Number -> numberText(value.toDouble())
            is List<*> -> value.joinToString(",", "[", "]") { literalText(it as UiModel.Expr) }
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (name, item) ->
                NeutralJson.quote(name as String) + ":" + literalText(item as UiModel.Expr)
            }
            null -> "null"
            else -> throw IllegalStateException("$PACK_MISMATCH default: unsupported literal")
        }
    }
}

/** Resolution of one node's committed props from the generated declarations only. */
internal sealed interface Resolution {
    data class Resolved(val values: Map<String, UiValue>) : Resolution
    data class Fault(val component: String, val prop: String, val reason: String) : Resolution
}

internal object PropResolution {
    fun kindMatches(kind: String, value: UiValue): Boolean = when (kind) {
        "string" -> value is UiValue.Text
        "number" -> value is UiValue.Num
        "boolean" -> value is UiValue.Flag
        "json" -> value is UiValue.Json
        "numbers" -> value is UiValue.Json
        "action" -> value is UiValue.Slot
        else -> false
    }

    fun fromDefault(default: PropDefault): UiValue = when (default) {
        is PropDefault.Text -> UiValue.Text(default.value)
        is PropDefault.Num -> UiValue.Num(default.value)
        is PropDefault.Flag -> UiValue.Flag(default.value)
        is PropDefault.Json -> UiValue.Json(NeutralJson.parse(default.value) ?: JsonValue.Null)
    }

    /**
     * Exact-kind values only. A declared default applies to an omitted prop; an omitted
     * optional prop with no default is absent; an omitted required prop is unbound.
     */
    fun resolve(component: String, props: List<UiProp>): Resolution {
        val declarations = PackPropDeclarations.forComponent(component)
        val supplied = linkedMapOf<String, UiValue>()
        for (prop in props) {
            val declaration = declarations.firstOrNull { it.prop == prop.name }
                ?: return Resolution.Fault(component, prop.name, "undeclared")
            if (!kindMatches(declaration.kind, prop.value)) return Resolution.Fault(component, prop.name, "kind")
            supplied[prop.name] = prop.value
        }
        val values = linkedMapOf<String, UiValue>()
        for (declaration in declarations) {
            val value = supplied[declaration.prop]
            if (value != null) { values[declaration.prop] = value; continue }
            val default = declaration.default
            if (default != null) { values[declaration.prop] = fromDefault(default); continue }
            if (declaration.optional) continue
            return Resolution.Fault(component, declaration.prop, "unbound")
        }
        return Resolution.Resolved(values)
    }
}

/** Style families and the closed icon set. Style never faults; an unknown icon does. */
internal object StyleFamilies {
    private val text = listOf("body", "display", "title", "heading", "caption", "eyebrow", "metric", "muted", "success", "warning", "danger")
    private val containers = listOf("default", "compact", "spacious", "center", "end", "surface", "accent", "split", "joined")
    private val lists = listOf("default", "compact", "comfortable")
    private val grids = listOf("default", "compact", "spacious")
    private val motions = listOf("pop", "pulse", "jelly", "press", "flash", "breathe", "shake", "nod", "wobble", "swing",
                                 "recoil", "bounce", "hop", "jump", "fall", "spin", "turn", "tumble", "flip", "fade",
                                 "rise", "zoom", "slide", "float", "drift")
    private val common = listOf("default", "accent", "success", "warning", "danger", "muted")
    private val families = mapOf(
        "Text" to (text to "body"), "NumberText" to (text to "body"), "ClockText" to (text to "body"),
        "Timer" to (text to "body"),
        "Column" to (containers to "default"), "Row" to (containers to "default"),
        "Card" to (listOf("surface", "accent", "success", "warning", "danger") to "surface"),
        "Button" to (listOf("primary", "secondary", "success", "danger") to "primary"),
        "Cell" to (listOf("primary", "secondary", "success", "danger") to "primary"),
        "IconButton" to (listOf("primary", "secondary", "success", "danger", "quiet") to "primary"),
        "Section" to (listOf("content", "hero", "summary", "supporting", "warning") to "content"),
        "AppTheme" to (listOf("clean", "soft", "expressive", "editorial", "technical", "playful") to "clean"),
        "ListGroup" to (lists to "default"), "Timeline" to (lists to "default"),
        "ListItem" to (lists to "default"), "TimelineItem" to (lists to "default"),
        "NumberListItem" to (lists to "default"),
        "Grid" to (grids to "default"), "MetricGroup" to (grids to "default"),
        "KeyValueGroup" to (grids to "default"), "AdaptiveColumns" to (grids to "default"),
        "Hero" to (listOf("plain", "accent") to "plain"),
        "Header" to (listOf("default", "center") to "default"),
        "ActionBar" to (listOf("default", "end", "center", "spread") to "default"),
        "Spacer" to (listOf("none", "small", "default", "large") to "default"),
        "TextField" to (listOf("default", "multiline") to "default"),
        "Transform" to (listOf("default", "solid") to "default"),
        "Stage" to (listOf("default", "floor") to "default"),
        "Surface" to (listOf("default", "fill", "clear") to "default"),
        "Motion" to (motions to "pop"),
    )
    private val icons = setOf("check", "close", "plus", "minus", "chevron-right", "navigate", "pin", "calendar",
                              "clock", "star", "user", "warning", "info", "trash", "list", "chart")

    private fun family(component: String): Pair<List<String>, String> = families[component] ?: (common to "default")

    fun resolve(component: String, style: String): String {
        val (allowed, fallback) = family(component)
        if (style.isEmpty()) return fallback
        return if (style in allowed) style else fallback
    }

    fun icon(name: String): Boolean = name.isEmpty() || name in icons
}
