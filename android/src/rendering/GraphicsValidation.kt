package dev.deal.embedding

/** Graphics validity is decided before painting; a violation faults without truncation. */
internal object GraphicsValidation {
    private const val MAX_SHAPES = 4000
    private const val MAX_DEPTH = 12
    private const val MAX_POINT_NUMBERS = 2000
    private val colour = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val align = setOf("left", "center", "right")
    private val arity = mapOf('M' to 2, 'L' to 2, 'C' to 6, 'Q' to 4, 'A' to 7, 'Z' to 0)
    private val variants = mapOf(
        "rect" to listOf("x", "y", "width", "height"),
        "roundRect" to listOf("x", "y", "width", "height", "radius"),
        "circle" to listOf("x", "y", "radius"),
        "arc" to listOf("x", "y", "radius", "startDegrees", "sweepDegrees", "lineWidth"),
        "line" to listOf("x1", "y1", "x2", "y2", "lineWidth"),
        "polyline" to listOf("points", "lineWidth"),
        "polygon" to listOf("points"),
        "path" to listOf("commands"),
        "strokePath" to listOf("commands", "lineWidth"),
        "text" to listOf("x", "y", "size", "align", "text", "bold"),
        "group" to listOf("x", "y", "rotation", "scale", "alpha", "shapes"),
    )
    private val optional = mapOf(
        "rect" to setOf("x", "y", "width", "height", "color"),
        "roundRect" to setOf("x", "y", "width", "height", "radius", "color"),
        "circle" to setOf("x", "y", "radius", "color"),
        "arc" to setOf("x", "y", "radius", "startDegrees", "sweepDegrees", "lineWidth", "color"),
        "line" to setOf("x1", "y1", "x2", "y2", "lineWidth", "color"),
        "polyline" to setOf("points", "lineWidth", "color"),
        "polygon" to setOf("points", "color"),
        "path" to setOf("commands", "color"),
        "strokePath" to setOf("commands", "lineWidth", "color"),
        "text" to setOf("x", "y", "size", "align", "text", "bold", "color"),
        "group" to setOf("x", "y", "rotation", "scale", "alpha", "shapes"),
    )

    /** Validates one frame of shapes: entries, variants, fields, colours, points, counts and depth. */
    fun shapes(value: JsonValue?): String? {
        val items = (value as? JsonValue.Arr)?.items ?: return if (value == null) "shapes missing" else "shapes must be an array"
        val count = intArrayOf(0)
        return visit(items, 0, count)
    }

    /** Shape count of a validated frame (one shape per entry, groups included). */
    fun shapeCount(value: JsonValue?): Int {
        val items = (value as? JsonValue.Arr)?.items ?: return 0
        var count = 0
        fun visit(list: List<JsonValue>) {
            for (item in list) {
                count++
                val fields = (item as? JsonValue.Obj)?.fields ?: continue
                (fields["shapes"] as? JsonValue.Arr)?.let { visit(it.items) }
            }
        }
        visit(items)
        return count
    }

    /** Validates a graphics.Pointer payload. */
    fun pointer(value: JsonValue?): String? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return "pointer must be an object"
        val phase = (fields["phase"] as? JsonValue.Str)?.value
        if (phase !in setOf("down", "move", "up", "cancel")) return "phase must be down, move, up or cancel"
        val id = fields["id"]
        if (id !is JsonValue.Num || id.value < 0 || id.value != kotlin.math.floor(id.value)) return "id must be a nonnegative integer"
        for (name in listOf("x", "y")) {
            val number = fields[name]
            if (number !is JsonValue.Num || !number.value.isFinite()) return "$name must be finite"
        }
        return null
    }

    /** Absolute SVG path data with the exact parameter count each command requires. */
    private fun commandIssue(data: String): String? {
        var index = 0
        var command: Char? = null
        var expected = 0
        var supplied = 0
        var first: Char? = null
        while (index < data.length) {
            val character = data[index]
            when {
                character.isWhitespace() || character == ',' -> index++
                character.isLetter() -> {
                    val value = arity[character] ?: return "must be absolute M/L/C/Q/A/Z"
                    // SVG repeats a command's coordinate sets without repeating the letter, but a
                    // command letter always carries at least one complete set.
                    if (command != null && (supplied < expected || expected == 0 && supplied != 0 || expected > 0 && supplied % expected != 0)) {
                        return "command $command requires at least one complete set of $expected numbers"
                    }
                    if (first == null) first = character
                    command = character
                    expected = value
                    supplied = 0
                    index++
                }
                character.isDigit() || character in "+-." -> {
                    if (command == null) return "must start with a command"
                    if (expected == 0) return "command $command takes no numbers"
                    val start = index
                    if (character == '+' || character == '-') index++
                    while (index < data.length && (data[index].isDigit() || data[index] in ".eE" ||
                            ((data[index] == '+' || data[index] == '-') && data[index - 1] in "eE"))) index++
                    val number = data.substring(start, index).toDoubleOrNull() ?: return "has an invalid number"
                    if (!number.isFinite()) return "has a nonfinite number"
                    if (command == 'A') {
                        val position = supplied % 7
                        if (position < 2 && number < 0) return "has a negative arc radius"
                        if ((position == 3 || position == 4) && number != 0.0 && number != 1.0) return "has an arc flag other than 0 or 1"
                    }
                    supplied++
                }
                else -> return "has an unsupported character '$character'"
            }
        }
        if (command != null && (supplied < expected || expected == 0 && supplied != 0 || expected > 0 && supplied % expected != 0)) {
            return "command $command requires at least one complete set of $expected numbers"
        }
        if (first != 'M') return "must start with M"
        return null
    }

    private fun visit(items: List<JsonValue>, depth: Int, count: IntArray): String? {
        if (depth > MAX_DEPTH) return "depth exceeds $MAX_DEPTH"
        for (item in items) {
            count[0]++
            if (count[0] > MAX_SHAPES) return "more than $MAX_SHAPES shapes"
            val fields = (item as? JsonValue.Obj)?.fields ?: return "shape must be an object"
            val tag = (fields["tag"] as? JsonValue.Str)?.value ?: return "tag is required"
            val required = variants[tag] ?: return "unsupported tag $tag"
            val allowed = optional.getValue(tag)
            for (name in required) if (name !in fields) return "$tag requires $name"
            for (name in fields.keys) if (name != "tag" && name !in allowed) return "$tag does not accept $name"
            if (tag != "group") {
                val color = fields["color"]
                if (color == null || color !is JsonValue.Str || !colour.matches(color.value)) return "$tag requires a #RRGGBB or #RRGGBBAA colour"
                for (name in listOf("width", "height", "radius", "size", "scale")) fields[name]?.let { number ->
                    if (number !is JsonValue.Num || number.value < 0) return "$tag $name must be finite and nonnegative"
                }
            }
            for (name in listOf("x", "y", "x1", "y1", "x2", "y2", "startDegrees", "sweepDegrees", "rotation")) {
                val number = fields[name] ?: continue
                if (number !is JsonValue.Num || !number.value.isFinite()) return "$tag $name must be finite"
            }
            (fields["lineWidth"] as? JsonValue.Num)?.let { if (it.value < 0) return "$tag lineWidth must be nonnegative" }
            if ("lineWidth" in required) {
                val width = fields["lineWidth"]
                if (width !is JsonValue.Num || width.value < 0) return "$tag lineWidth must be a finite nonnegative number"
            }
            (fields["alpha"] as? JsonValue.Num)?.let { if (it.value < 0 || it.value > 1) return "$tag alpha must be in [0, 1]" }
            if (tag == "group") {
                val alpha = fields["alpha"]
                if (alpha !is JsonValue.Num || alpha.value < 0 || alpha.value > 1) return "group alpha must be in [0, 1]"
                val scale = fields["scale"]
                if (scale !is JsonValue.Num || scale.value < 0) return "group scale must be nonnegative"
                val nested = (fields["shapes"] as? JsonValue.Arr)?.items ?: return "group requires shapes"
                visit(nested, depth + 1, count)?.let { return it }
                continue
            }
            (fields["points"] as? JsonValue.Arr)?.let { points ->
                if (points.items.size % 2 != 0) return "$tag points must be coordinate pairs"
                // The bound counts point coordinates: the spec's 1000 points are two numbers each.
                if (points.items.size > MAX_POINT_NUMBERS) return "$tag exceeds ${MAX_POINT_NUMBERS / 2} points"
                for (number in points.items) if (number !is JsonValue.Num || !number.value.isFinite()) return "$tag points must be finite"
            }
            if ("points" in required && fields["points"] !is JsonValue.Arr) return "$tag requires points"
            (fields["commands"] as? JsonValue.Str)?.let { parsed ->
                commandIssue(parsed.value)?.let { return "$tag commands $it" }
            }
            if ("commands" in required && fields["commands"] !is JsonValue.Str) return "$tag requires commands"
            if (tag == "text") {
                val alignment = (fields["align"] as? JsonValue.Str)?.value
                if (alignment !in align) return "text align must be left, center or right"
                if (fields["text"] !is JsonValue.Str) return "text requires text"
                if (fields["bold"] !is JsonValue.Flag) return "text requires bold"
                val size = fields["size"]
                if (size !is JsonValue.Num || size.value < 0) return "text size must be finite and nonnegative"
            }
        }
        return null
    }
}
