package dev.deal.embedding

/** One ingress fault record; the caller keeps its previous tree and retains the fault. */
internal data class IngressFault(
    val code: String,
    val component: String,
    val prop: String? = null,
    val propClass: String? = null,
    val count: Int? = null,
    val detail: String? = null,
)

internal sealed interface IngressVerdict {
    data object Accepted : IngressVerdict
    data class Rejected(val fault: IngressFault) : IngressVerdict
}

/**
 * Whole-tree ingress verdict over the committed tree and the candidate's issued target.
 * Renders nothing, changes no state and retains nothing.
 */
internal object IngressValidation {
    private val targets = setOf("screen", "chat", "card")
    private val rootNames = setOf("root", "__root")

    private class Counters {
        var pictures = 0
        var motions = 0
        var timers = 0
    }

    fun validate(tree: UiNode, target: String?): IngressVerdict {
        if (target == null || target !in targets) {
            return IngressVerdict.Rejected(IngressFault("ADMISSION_TARGET", "", detail = "issued target is absent or outside screen, chat and card"))
        }
        val counters = Counters()
        val fault = walk(tree, target == "card", counters) ?: return IngressVerdict.Accepted
        return IngressVerdict.Rejected(fault)
    }

    private fun walk(node: UiNode, card: Boolean, counters: Counters): IngressFault? {
        val name = node.component
        if (name in rootNames) {
            for (child in node.children) walk(child, card, counters)?.let { return it }
            return null
        }
        if (RenderComponent.entry(name) == null) return IngressFault("PROP_FAULT", name, detail = "undeclared")
        val resolved = when (val resolution = PropResolution.resolve(name, node.props)) {
            is Resolution.Fault -> return IngressFault("PROP_FAULT", name, resolution.prop, detail = resolution.reason)
            is Resolution.Resolved -> resolution.values
        }
        if (card) {
            if (name !in PackCardProfile.components) {
                return IngressFault("CARD_FAULT", name, detail = "component is outside the card profile")
            }
            for (event in RendererEvents.payloads[name].orEmpty().keys) {
                if ("$name.$event" !in PackCardProfile.events && resolved[event] is UiValue.Slot) {
                    return IngressFault("CARD_FAULT", name, event, detail = "event is outside the card profile")
                }
            }
            if (name == "Path" && ((resolved["spinPeriodMs"] as? UiValue.Num)?.value ?: 0.0) != 0.0) {
                return IngressFault("CARD_FAULT", name, "spinPeriodMs", detail = "unsupported prop for the card target")
            }
        }
        val presentation = propsClass(name)
        for ((prop, value) in resolved) {
            val field = presentation.firstOrNull { it.prop == prop }
            if (field?.kind != "number") continue
            val number = (value as? UiValue.Num)?.value ?: continue
            NumericPresentation.fault(name, prop, number)?.let { return it }
        }
        // A tab label is a string, and one label is required per child. A non-string item is
        // rejected here, before the selection rule, because Values.strings would otherwise drop it
        // and leave an accepted tree with fewer labels than children and therefore no panel. The
        // remaining string[] props (Select labels, Sparkline categories, Table headers, TableRow
        // cells) still rely on the loose json declaration kind; a strict strings kind shared by them
        // is a separate ingress item, not this one.
        if (name == "Tabs") {
            val labels = (resolved["labels"] as? UiValue.Json)?.value
            if (labels !is JsonValue.Arr || labels.items.any { it !is JsonValue.Str }) {
                return IngressFault("PROP_FAULT", name, "labels", detail = "labels must be an array of strings")
            }
        }
        for ((prop, value) in resolved) {
            val field = presentation.firstOrNull { it.prop == prop } ?: continue
            if (field.kind == "number" || field.kind == "json" || field.kind == "numbers") {
                classFault(name, node, resolved, prop, value, field.kind)?.let { return it }
            }
        }
        styleFault(name, resolved)?.let { return it }
        if (name == "Surface") {
            val shapes = (resolved["shapes"] as? UiValue.Json)?.value
            GraphicsValidation.shapes(shapes)?.let { return IngressFault("PROP_FAULT", name, "shapes", detail = it) }
        }
        if (card) {
            when (name) {
                "Surface" -> {
                    counters.pictures++
                    if (counters.pictures > PackCardProfile.pictures) {
                        return IngressFault("CARD_FAULT", name, count = PackCardProfile.pictures, detail = "picture limit")
                    }
                    val count = GraphicsValidation.shapeCount((resolved["shapes"] as? UiValue.Json)?.value)
                    if (count > PackCardProfile.shapesPerPicture) {
                        return IngressFault("CARD_FAULT", name, "shapes", count = PackCardProfile.shapesPerPicture, detail = "shape limit per picture")
                    }
                }
                "Sparkline" -> {
                    counters.pictures++
                    if (counters.pictures > PackCardProfile.pictures) {
                        return IngressFault("CARD_FAULT", name, "series", count = PackCardProfile.pictures, detail = "picture limit")
                    }
                    val points = ((resolved["series"] as? UiValue.Json)?.value as? JsonValue.Arr)?.items?.size ?: 0
                    val second = ((resolved["series2"] as? UiValue.Json)?.value as? JsonValue.Arr)?.items?.size ?: 0
                    if (points + second > PackCardProfile.shapesPerPicture) {
                        return IngressFault("CARD_FAULT", name, "series", count = PackCardProfile.shapesPerPicture, detail = "shape limit per picture")
                    }
                }
                "Motion" -> {
                    counters.motions++
                    if (counters.motions > PackCardProfile.motions) {
                        return IngressFault("CARD_FAULT", name, count = PackCardProfile.motions, detail = "motion limit")
                    }
                }
                "Timer" -> {
                    counters.timers++
                    if (counters.timers > PackCardProfile.nativeTimers) {
                        return IngressFault("CARD_FAULT", name, count = PackCardProfile.nativeTimers, detail = "native timer limit")
                    }
                }
            }
        }
        for (child in node.children) walk(child, card, counters)?.let { return it }
        return null
    }

    private fun propsClass(component: String) = PackPropDeclarations.forComponent(component)

    private fun styleFault(component: String, values: Map<String, UiValue>): IngressFault? {
        val icon = (values["icon"] as? UiValue.Text)?.value ?: ""
        if (!StyleFamilies.icon(icon)) return IngressFault("PROP_FAULT", component, "icon", detail = "unknown icon $icon")
        if (component == "Icon") {
            val iconName = (values["name"] as? UiValue.Text)?.value ?: ""
            if (!StyleFamilies.icon(iconName)) return IngressFault("PROP_FAULT", component, "name", detail = "unknown icon $iconName")
        }
        if (component == "Tile") {
            val span = (values["span"] as? UiValue.Num)?.value
            if (span != null && span != 1.0 && span != 2.0) {
                return IngressFault("PROP_FAULT", component, "span", detail = "span must be 1 or 2")
            }
        }
        return null
    }

    private fun classFault(component: String, node: UiNode, values: Map<String, UiValue>, prop: String, value: UiValue,
                           kind: String): IngressFault? {
        val identity = "$component.$prop"
        val propClass = PackConstraints.classes[identity] ?: "finite"
        val number = (value as? UiValue.Num)?.value
        fun fault(detail: String? = null) = IngressFault("PROP_FAULT", component, prop, propClass, detail = detail)
        when (propClass) {
            "finite" -> {
                if (number != null) return if (number.isFinite()) null else fault()
                if (kind != "numbers") return null
                val items = ((value as? UiValue.Json)?.value as? JsonValue.Arr)?.items ?: return fault("expected a number array")
                for (item in items) if (item !is JsonValue.Num || !item.value.isFinite()) return fault()
                return null
            }
            "nonnegative" -> return if (number != null && number.isFinite() && number >= 0) null else fault()
            "positive" -> return if (number != null && number.isFinite() && number > 0) null else fault()
            "integral-index" -> return integralFault(component, node, values, prop, number, ::fault)
            "chart" -> {
                if (number == null || !number.isFinite()) return fault()
                val series = ((values["series"] as? UiValue.Json)?.value as? JsonValue.Arr)?.items ?: emptyList()
                val second = ((values["series2"] as? UiValue.Json)?.value as? JsonValue.Arr)?.items ?: emptyList()
                if (series.isEmpty() && second.isEmpty()) return null
                val minimum = (values["minimum"] as? UiValue.Num)?.value ?: 0.0
                return if (number > minimum) null else fault("maximum must exceed minimum")
            }
            "pose" -> {
                val entries = ((value as? UiValue.Json)?.value as? JsonValue.Arr)?.items ?: return fault()
                if (entries.size != 8) return fault("pose requires exactly eight entries")
                for (entry in entries) if (entry !is JsonValue.Num || !entry.value.isFinite()) return fault()
                val opacity = entries[7] as JsonValue.Num
                return if (opacity.value < 0 || opacity.value > 1) fault("pose opacity must be in [0, 1]") else null
            }
            "display-clamp" -> return null
        }
        return null
    }

    private fun integralFault(component: String, node: UiNode, values: Map<String, UiValue>, prop: String,
                              number: Double?, fault: (String?) -> IngressFault): IngressFault? {
        if (number == null || !number.isFinite() || number != kotlin.math.floor(number)) return fault(null)
        fun array(name: String): List<JsonValue>? = ((values[name] as? UiValue.Json)?.value as? JsonValue.Arr)?.items
        return when {
            prop == "step" -> if (number > 0) null else fault("step must be a positive integer")
            prop == "selected" && component == "Select" -> {
                val labels = array("labels") ?: return fault(null)
                if (labels.isEmpty()) { if (number == -1.0) null else fault("selected must be -1 when labels are empty") }
                else if (number >= 0 && number < labels.size) null else fault("selected must index the labels")
            }
            prop == "selected" && component == "Tabs" -> {
                val labels = array("labels") ?: return fault(null)
                if (number < 0 || number >= labels.size) fault("selected must index the labels")
                else if (labels.size != node.children.size) fault("one label per child is required")
                else null
            }
            prop == "columns" && component == "Table" -> {
                if (number < 0) fault(null) else {
                    val headers = array("headers")
                    if (headers != null && headers.size != number.toInt()) fault("columns must equal the headers length")
                    else {
                        val bad = node.children.firstOrNull { child ->
                            val cells = ((child.prop("cells") as? UiValue.Json)?.value as? JsonValue.Arr)?.items
                            cells != null && cells.size != number.toInt()
                        }
                        if (bad != null) fault("columns must equal every row's cell count") else null
                    }
                }
            }
            prop == "columns" -> if (number >= 0) null else fault(null)
            else -> null
        }
    }
}
