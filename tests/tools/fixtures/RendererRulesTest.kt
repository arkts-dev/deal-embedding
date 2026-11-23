package dev.deal.embedding

import java.nio.file.Files
import java.nio.file.Path

/** Deterministic rule items: metadata, prop resolution, classes, presentation, style, events,
 * graphics, card profile, frame targeting, Tabs panels, draft presentation, dispatch completeness
 * and the display vocabulary. */

private fun text(name: String, value: String) = UiProp(name, UiValue.Text(value))
private fun number(name: String, value: Double) = UiProp(name, UiValue.Num(value))
private fun flag(name: String, value: Boolean) = UiProp(name, UiValue.Flag(value))
private fun action(name: String, slot: Int = 1) = UiProp(name, UiValue.Slot(slot))
private fun structured(name: String, json: String) =
    UiProp(name, UiValue.Json(NeutralJson.parse(json) ?: error("fixture JSON is invalid: $json")))
private fun node(component: String, props: List<UiProp> = emptyList(), children: List<UiNode> = emptyList()) =
    UiNode(component, props, children)

private fun verdict(tree: UiNode, target: String? = "screen") = IngressValidation.validate(tree, target)

private fun accepted(tree: UiNode, target: String = "screen") {
    val value = verdict(tree, target)
    check(value is IngressVerdict.Accepted) { "expected acceptance for ${tree.component} under $target, got $value" }
}

private fun rejected(tree: UiNode, code: String, component: String, prop: String? = null,
                     propClass: String? = null, target: String? = "screen"): IngressFault {
    val value = verdict(tree, target)
    check(value is IngressVerdict.Rejected) { "expected a fault for ${tree.component} under $target" }
    val fault = value.fault
    check(fault.code == code) { "expected $code, got $fault" }
    check(fault.component == component) { "expected component $component, got $fault" }
    if (prop != null) check(fault.prop == prop) { "expected prop $prop, got $fault" }
    if (propClass != null) check(fault.propClass == propClass) { "expected class $propClass, got $fault" }
    return fault
}

private fun surface(width: Double = 10.0, shapes: String = "[]", events: List<UiProp> = emptyList(), height: Double = 10.0) =
    node("Surface", listOf(number("width", width), number("height", height), structured("shapes", shapes)) + events)

private fun rectShapes(count: Int): String = (0 until count).joinToString(",", "[", "]") {
    """{"tag":"rect","x":0,"y":0,"width":1,"height":1,"color":"#112233"}"""
}

private val validShapes = """[
  {"tag":"roundRect","x":0,"y":0,"width":10,"height":10,"radius":2,"color":"#112233"},
  {"tag":"line","x1":0,"y1":0,"x2":4,"y2":4,"lineWidth":1,"color":"#112233"},
  {"tag":"group","x":0,"y":0,"rotation":0,"scale":1,"alpha":0.5,"shapes":[
    {"tag":"text","x":0,"y":0,"size":12,"align":"center","text":"hi","bold":false,"color":"#112233AA"}]}
]"""

private fun rulesPack(root: Path): deal.ui.UiModel.PackModule {
    val path = root.resolve("ui/platform.dealui-pack")
    return deal.ui.UiParser.parsePack(path, Files.readString(path))
}

private fun expectMismatch(block: () -> Unit, label: String) {
    val failure = runCatching(block).exceptionOrNull()
    check(failure is IllegalStateException && failure.message!!.contains(PACK_MISMATCH)) {
        "$label must be rejected by renderer conformance, got $failure"
    }
}

fun main(args: Array<String>) {
    val root = Path.of(args[0])
    val pack = rulesPack(root)

    // Metadata: one entry per pack component, canonical ordering, derived unique capability.
    check(RenderComponent.entries.map { it.component } == RenderComponent.entries.map { it.component }.sorted()) {
        "Renderer entries must be ordered by component name"
    }
    check(RenderComponent.entries.map { it.capability }.toSet().size == RenderComponent.entries.size) {
        "Capabilities must be unique"
    }
    check(RenderComponent.entries.all { it.capability == "renderer.${it.component}" })
    check(RenderComponent.entries.map { it.component }.toSet() == pack.components().keys)
    RenderComponent.verify(pack)

    // Prop resolution: defaults only for omitted optional props, absence for optional without default.
    accepted(surface())
    accepted(node("Button", listOf(text("text", "Go"))))
    accepted(node("Toggle", listOf(text("label", "On"), flag("checked", false))))
    accepted(node("Text", listOf(text("text", "Body"))))
    rejected(node("Surface", listOf(number("height", 1.0), structured("shapes", "[]"))), "PROP_FAULT", "Surface", "width")
    rejected(node("Transform", listOf(number("durationMs", 100.0))), "PROP_FAULT", "Transform", "pose")
    rejected(node("Text", listOf(text("text", "Body"), text("value", "legacy"))), "PROP_FAULT", "Text", "value")
    rejected(node("Text", listOf(number("text", 1.0))), "PROP_FAULT", "Text", "text")
    check(PropResolution.resolve("Button", listOf(UiProp("nope", UiValue.Text("x")))) is Resolution.Fault)
    check(PropResolution.resolve("Toggle", listOf(text("label", "On"), flag("checked", true))) is Resolution.Resolved)

    // Seven prop-value classes: one acceptance and one rejection each at ingress.
    accepted(node("Button", listOf(text("text", "x"), number("value", 1.0))))
    rejected(node("Button", listOf(text("text", "x"), number("value", Double.NaN))), "PROP_FAULT", "Button", "value", "finite")
    rejected(surface(width = Double.NaN), "PROP_FAULT", "Surface", "width", "nonnegative")
    accepted(node("Layers", listOf(number("width", 0.0), number("height", 1.0))))
    rejected(node("Layers", listOf(number("width", -1.0), number("height", 1.0))), "PROP_FAULT", "Layers", "width", "nonnegative")
    accepted(node("Timer", listOf(number("millis", -86_400_000.0), flag("down", false))))
    accepted(node("Timer", listOf(number("millis", 0.0), flag("down", true))))
    accepted(node("Timer", listOf(number("millis", 1_700_000_000_000.0), flag("down", true))))
    rejected(node("Timer", listOf(number("millis", Double.NaN), flag("down", true))), "PROP_FAULT", "Timer", "millis", "finite")
    rejected(node("Timer", listOf(number("millis", Double.POSITIVE_INFINITY), flag("down", false))),
        "PROP_FAULT", "Timer", "millis", "finite")
    accepted(node("Ticker", listOf(text("id", "t"), number("intervalMs", 1.0), flag("running", false), action("onTick"))))
    rejected(node("Ticker", listOf(text("id", "t"), number("intervalMs", 0.0), flag("running", false), action("onTick"))),
        "PROP_FAULT", "Ticker", "intervalMs", "positive")
    rejected(node("Progress", listOf(text("label", "p"), number("value", 0.0), number("maximum", 0.0))),
        "PROP_FAULT", "Progress", "maximum", "positive")
    accepted(node("AdaptiveColumns", listOf(number("minimumWidth", 240.0))))
    rejected(node("AdaptiveColumns", listOf(number("minimumWidth", 0.0))), "PROP_FAULT", "AdaptiveColumns", "minimumWidth", "positive")
    accepted(node("Table", listOf(structured("headers", """["a","b"]"""), number("columns", 2.0)),
        listOf(node("TableRow", listOf(structured("cells", """["1","2"]"""))))))
    rejected(node("Table", listOf(structured("headers", """["a","b"]"""), number("columns", 1.5))),
        "PROP_FAULT", "Table", "columns", "integral-index")
    rejected(node("Table", listOf(structured("headers", """["a","b"]"""), number("columns", 2.0)),
        listOf(node("TableRow", listOf(structured("cells", """["1"]"""))))),
        "PROP_FAULT", "Table", "columns", "integral-index")
    rejected(node("NumberField", listOf(text("label", "n"), number("value", 1.0), number("minimum", 0.0), number("maximum", 9.0), number("step", 0.0))), "PROP_FAULT", "NumberField", "step", "integral-index")
    accepted(node("Select", listOf(structured("labels", "[]"), number("selected", -1.0))))
    rejected(node("Select", listOf(structured("labels", """["a"]"""), number("selected", 3.0))),
        "PROP_FAULT", "Select", "selected", "integral-index")
    // Tabs require one label per child, string labels and an integral index-valid selection.
    fun tabs(labels: String, selected: Double, children: List<UiNode>) =
        node("Tabs", listOf(structured("labels", labels), number("selected", selected), action("onChange")), children)
    val firstTab = UiNode("Column", emptyList(), listOf(node("TextField", listOf(text("label", "Note"), text("value", "a"), action("onChange")))), key = "first")
    val secondTab = UiNode("Column", emptyList(), listOf(node("Button", listOf(text("text", "Apply"), action("onClick")))), key = "second")
    val tabChildren = listOf(firstTab, secondTab)
    accepted(tabs("""["first","second"]""", 0.0, tabChildren))
    accepted(tabs("""["first","second"]""", 1.0, tabChildren))
    rejected(tabs("""["first","second"]""", -1.0, tabChildren), "PROP_FAULT", "Tabs", "selected", "integral-index")
    rejected(tabs("""["first","second"]""", 2.0, tabChildren), "PROP_FAULT", "Tabs", "selected", "integral-index")
    rejected(tabs("""["first","second"]""", 0.5, tabChildren), "PROP_FAULT", "Tabs", "selected", "integral-index")
    rejected(tabs("""["first","second"]""", Double.NaN, tabChildren), "PROP_FAULT", "Tabs", "selected", "integral-index")
    // One label per child, in both directions.
    rejected(tabs("""["first"]""", 0.0, tabChildren), "PROP_FAULT", "Tabs")
    rejected(tabs("""["first","second","third"]""", 0.0, tabChildren), "PROP_FAULT", "Tabs")
    // Unlike Select, Tabs never accepts the empty -1 selection.
    rejected(tabs("[]", -1.0, emptyList()), "PROP_FAULT", "Tabs", "selected", "integral-index")
    rejected(tabs("""["first",1]""", 1.0, tabChildren), "PROP_FAULT", "Tabs", "labels")
    // A label defect stays a label defect even where the selection is also out of range.
    rejected(tabs("""["first",1]""", 5.0, tabChildren), "PROP_FAULT", "Tabs", "labels")
    rejected(tabs("5", 0.0, tabChildren), "PROP_FAULT", "Tabs", "labels")
    accepted(node("Sparkline", listOf(structured("series", "[1,2]"), number("maximum", 5.0))))
    accepted(node("Sparkline", listOf(structured("series", "[]"), number("maximum", 0.0))))
    accepted(node("Sparkline", listOf(structured("series", "[]"), structured("series2", "[]"), number("maximum", 0.0))))
    rejected(node("Sparkline", listOf(structured("series", "[1,\"x\"]"), number("maximum", 5.0))),
        "PROP_FAULT", "Sparkline", "series", "finite")
    rejected(node("Sparkline", listOf(structured("series", "[1,null]"), number("maximum", 5.0))),
        "PROP_FAULT", "Sparkline", "series", "finite")
    rejected(node("Sparkline", listOf(structured("series", "[1,2]"), structured("series2", "[true]"), number("maximum", 5.0))),
        "PROP_FAULT", "Sparkline", "series2", "finite")
    val nonfiniteSeries = UiProp("series", UiValue.Json(JsonValue.Arr(listOf(JsonValue.Num(1.0), JsonValue.Num(Double.POSITIVE_INFINITY)))))
    rejected(node("Sparkline", listOf(nonfiniteSeries, number("maximum", 5.0))), "PROP_FAULT", "Sparkline", "series", "finite")
    rejected(node("Sparkline", listOf(structured("series", "5"), number("maximum", 5.0))), "PROP_FAULT", "Sparkline", "series", "finite")
    rejected(node("Sparkline", listOf(structured("series", "[1,2]"), number("maximum", 0.0))), "PROP_FAULT", "Sparkline", "maximum", "chart")
    val identity = "[0,0,0,0,0,0,1,1]"
    accepted(node("Transform", listOf(structured("pose", identity), number("durationMs", 10.0))))
    rejected(node("Transform", listOf(structured("pose", "[0,0,0,0,0]"), number("durationMs", 10.0))),
        "PROP_FAULT", "Transform", "pose", "pose")
    rejected(node("Transform", listOf(structured("pose", "[0,0,0,0,0,0,1,3]"), number("durationMs", 10.0))),
        "PROP_FAULT", "Transform", "pose", "pose")
    val infinite = UiProp("pose", UiValue.Json(JsonValue.Arr((0 until 8).map { JsonValue.Num(if (it == 1) Double.POSITIVE_INFINITY else 0.0) })))
    rejected(node("Transform", listOf(infinite, number("durationMs", 10.0))), "PROP_FAULT", "Transform", "pose", "pose")
    accepted(node("Progress", listOf(text("label", "p"), number("value", 150.0), number("maximum", 100.0))))
    check(NumericPresentation.format(150.0, "", "", 1, -1) == "150")

    // Shared numeric presentation domain and text.
    fun numberText(decimals: Double? = null, minimumDigits: Double? = null, value: Double = 1.0): UiNode {
        val props = mutableListOf(number("value", value))
        if (decimals != null) props.add(number("decimals", decimals))
        if (minimumDigits != null) props.add(number("minimumDigits", minimumDigits))
        return node("NumberText", props)
    }
    for (decimals in listOf(-1.0, 0.0, 6.0)) accepted(numberText(decimals))
    for (minimumDigits in listOf(1.0, 4.0)) accepted(numberText(minimumDigits = minimumDigits))
    for (decimals in listOf(-2.0, 7.0, 1.5)) rejected(numberText(decimals), "PROP_FAULT", "NumberText", "decimals")
    for (minimumDigits in listOf(0.0, 1.5)) rejected(numberText(minimumDigits = minimumDigits), "PROP_FAULT", "NumberText", "minimumDigits")
    rejected(numberText(value = Double.NaN), "PROP_FAULT", "NumberText", "value", "finite")
    check(NumericPresentation.format(1.0, "", "", 1, -1) == "1")
    check(NumericPresentation.format(0.5, "", "", 1, -1) == "0.5")
    check(NumericPresentation.format(1.0, "", "", 1, 6) == "1.000000")
    check(NumericPresentation.format(2.5, "", "", 1, 0) == "3")
    check(NumericPresentation.format(-2.5, "", "", 1, 0) == "-3")
    check(NumericPresentation.format(32.0, "", "", 4, -1) == "0032")
    check(NumericPresentation.format(-32.0, "", "", 4, -1) == "-0032")
    check(NumericPresentation.format(3.0, "€", " kg", 2, 0) == "€03 kg")
    check(NumericPresentation.format(1.0 / 3.0, "", "", 1, -1) == "0.3333333333333333")
    check(NumericPresentation.format(1e21, "", "", 1, -1) == "1000000000000000000000")
    check(NumericPresentation.display(4.0, mapOf("prefix" to UiValue.Text("#"), "suffix" to UiValue.Text("!"),
        "minimumDigits" to UiValue.Num(2.0), "decimals" to UiValue.Num(1.0))) == "#04.0!")

    // Style, icons, spans and accessibility: style never faults.
    accepted(node("Text", listOf(text("text", "x"), text("style", "unknown-style"))))
    check(StyleFamilies.resolve("Text", "") == "body")
    check(StyleFamilies.resolve("Text", "nonsense") == "body")
    // Timer carries the text style family: its painter asks the family whether the value is the
    // heading, which paints with distinct typography, and paints every other value as body.
    check(StyleFamilies.heading("Timer", "heading"))
    check(!StyleFamilies.heading("Timer", ""))
    check(!StyleFamilies.heading("Timer", "caption"))
    check(!StyleFamilies.heading("Timer", "nonsense"))
    check(StyleFamilies.resolve("Button", "primary") == "primary")
    check(StyleFamilies.resolve("Motion", "") == "pop") { "an empty Motion style takes its declared pop default" }
    accepted(node("Tile", listOf(text("label", "l"), text("value", "v"), number("span", 2.0))))
    rejected(node("Tile", listOf(text("label", "l"), text("value", "v"), number("span", 3.0))), "PROP_FAULT", "Tile", "span")
    rejected(node("Icon", listOf(text("name", "sparkle"))), "PROP_FAULT", "Icon", "name")
    rejected(node("Button", listOf(text("text", "x"), number("accessibilityLabel", 1.0))), "PROP_FAULT", "Button", "accessibilityLabel")

    // Event payload reconstruction.
    fun payload(component: String, event: String, values: Map<String, UiValue>, raw: String?) =
        EventPayloads.reconstruct(component, event, values, raw)
    check(payload("Toggle", "onChange", emptyMap(), "true") == EventPayload.Delivered("true"))
    check(payload("Toggle", "onChange", emptyMap(), "maybe") is EventPayload.Rejected)
    check(payload("Button", "onClick", mapOf("value" to UiValue.Num(7.0)), null) == EventPayload.Delivered("7"))
    check(payload("TextField", "onChange", emptyMap(), "typed 😀") == EventPayload.Delivered("typed 😀"))
    check(payload("Path", "onTap", mapOf("value" to UiValue.Num(3.0)), null) == EventPayload.Delivered("3"))
    check(payload("ListItem", "onTap", mapOf("eventValue" to UiValue.Num(4.0)), null) == EventPayload.Delivered("4"))
    check(payload("InsetBanner", "onAction", emptyMap(), null) == EventPayload.Delivered(null))
    check(payload("InsetBanner", "onAction", emptyMap(), "") is EventPayload.Rejected)
    check(payload("Button", "onFrame", emptyMap(), "1") == EventPayload.Undeclared)
    check(payload("Surface", "onFrame", emptyMap(), "16") == EventPayload.Delivered("16"))
    check(payload("Surface", "onFrame", emptyMap(), "-4") is EventPayload.Rejected)
    check(payload("Tabs", "onChange", emptyMap(), "1") == EventPayload.Delivered("1")) { "Tabs.onChange carries a number payload" }
    check(payload("Tabs", "onChange", emptyMap(), "second") is EventPayload.Rejected) { "a tab label is never a number payload" }
    check(payload("Surface", "onPointer", emptyMap(), """{"phase":"down","id":1,"x":2,"y":3}""") is EventPayload.Delivered)
    check(payload("Surface", "onPointer", emptyMap(), """{"phase":"hover","id":1,"x":2,"y":3}""") is EventPayload.Rejected)

    // Graphics validation: variants, fields, colours, commands, points, counts and depth.
    accepted(surface(shapes = validShapes))
    check(GraphicsValidation.pointer(NeutralJson.parse("""{"phase":"up","id":0,"x":1.5,"y":2}""")) == null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"rect","x":0,"y":0,"height":1,"color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"circle","x":0,"y":0,"radius":1,"lineWidth":1,"color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"rect","x":0,"y":0,"width":1,"height":1,"color":"#112233","alpha":1.5}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"polyline","points":[0,0,1],"lineWidth":1,"color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M 0 0 L 1 1","color":"#112233"}]""")) == null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"m 0 0 l 1 1","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M 0","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M 0 0 1 1","color":"#112233"}]""")) == null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M0 0 L1 1 2 2","color":"#112233"}]""")) == null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"C 1 2 3","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M 0 0 Z 1 1","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M 0 0 L","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M0 0 A-5 5 0 0 0 1 1","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M0 0 A5 5 0 2 0 1 1","color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"path","commands":"M0 0 A5 5 0 1 0 1 1","color":"#112233"}]""")) == null)
    val polyline2000 = "[" + (0 until 2000).joinToString(",") { "0" } + "]"
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"polyline","points":$polyline2000,"lineWidth":1,"color":"#112233"}]""")) == null)
    val polyline2002 = "[" + (0 until 2002).joinToString(",") { "0" } + "]"
    check(GraphicsValidation.shapes(NeutralJson.parse("""[{"tag":"polyline","points":$polyline2002,"lineWidth":1,"color":"#112233"}]""")) != null)
    check(GraphicsValidation.shapes(NeutralJson.parse(rectShapes(4001))) != null)
    val deep = (0 until 13).joinToString("", "", "") { """{"tag":"group","x":0,"y":0,"rotation":0,"scale":1,"alpha":1,"shapes":[""" } +
        """{"tag":"rect","x":0,"y":0,"width":1,"height":1,"color":"#112233"}""" + "]}".repeat(13)
    check(GraphicsValidation.shapes(NeutralJson.parse(deep)) != null)

    // Card profile: membership, events, limits and unsupported props selected by the issued target.
    fun cardTree(surfaces: Int, shapesPerSurface: Int = 60, motions: Int = 5, timers: Int = 3, extra: List<UiNode> = emptyList()) =
        node("root", children = (0 until surfaces).map { surface(shapes = rectShapes(shapesPerSurface)) } +
            (0 until motions).map { node("Motion", listOf(number("trigger", 1.0))) } +
            (0 until timers).map { node("Timer", listOf(number("millis", 1_700_000_000_000.0), flag("down", false))) } + extra)
    accepted(cardTree(5), "card")
    accepted(cardTree(5, 60, 5, 3, listOf(node("Path", listOf(text("commands", "M0 0 L1 1"))))), "card")
    rejected(cardTree(6), "CARD_FAULT", "Surface", target = "card")
    val sparkline = node("Sparkline", listOf(structured("series", "[1]"), number("maximum", 2.0)))
    rejected(cardTree(5, extra = listOf(sparkline)), "CARD_FAULT", "Sparkline", target = "card")
    accepted(cardTree(6), "screen")
    rejected(cardTree(5, 61), "CARD_FAULT", "Surface", target = "card")
    rejected(cardTree(5, 60, 6), "CARD_FAULT", "Motion", target = "card")
    rejected(cardTree(5, 60, 5, 4), "CARD_FAULT", "Timer", target = "card")
    rejected(cardTree(0, extra = listOf(node("Path", listOf(text("commands", "M0 0"), number("spinPeriodMs", 10.0))))),
        "CARD_FAULT", "Path", "spinPeriodMs", target = "card")
    val tile = node("Tile", listOf(text("label", "l"), text("value", "v")))
    val tileGrid = node("TileGrid", children = listOf(tile))
    rejected(cardTree(0, extra = listOf(tileGrid)), "CARD_FAULT", "TileGrid", target = "card")
    accepted(cardTree(0, extra = listOf(tileGrid)), "screen")
    rejected(cardTree(0), "ADMISSION_TARGET", "", target = "print")
    rejected(cardTree(0), "ADMISSION_TARGET", "", target = null)

    // Static and framed Surface: only the framed node carries a frame slot.
    check(surface().prop("onFrame") == null)
    fun slotsOf(component: String, props: List<UiProp>): Map<String, UiValue> =
        (PropResolution.resolve(component, props) as Resolution.Resolved).values
    val staticNode = surface(events = listOf(action("onPointer")))
    accepted(staticNode)
    val static = slotsOf("Surface", staticNode.props)
    check(static["onFrame"] == null) { "a static Surface subscribes to no frame" }
    check(static["onPointer"] is UiValue.Slot) { "a static Surface still delivers graphics.Pointer" }
    check(FrameTarget.plan((static["onFrame"] as? UiValue.Slot)?.value, previousMillis = 10L, nowMillis = 26L) == FramePlan.None) {
        "a frame offered to a static node dispatches nothing"
    }
    val framedNode = surface(events = listOf(action("onFrame"), action("onPointer")))
    accepted(framedNode)
    val frame = (slotsOf("Surface", framedNode.props)["onFrame"] as UiValue.Slot).value
    check(FrameTarget.plan(frame, null, 500L) == FramePlan.Frame(frame, 0L)) { "the first frame after activation is zero" }
    check(FrameTarget.plan(frame, 500L, 516L) == FramePlan.Frame(frame, 16L)) { "later frames carry the elapsed delay" }
    check(FrameTarget.plan(frame, null, 900L) == FramePlan.Frame(frame, 0L)) { "a frame after a resume starts from an absent previous value, so its delta is zero" }
    check(FrameTarget.plan(frame, 900L, 908L) == FramePlan.Frame(frame, 8L))
    check(FrameTarget.plan(frame, 908L, 900L) == FramePlan.Frame(frame, 0L)) { "a delta is never negative" }

    // Tabs panels: the rule returns exactly the selected child, never a sibling in its place.
    // These items pin the production rules as pure functions. The painter's composition of that panel,
    // its event delivery and its saveable state holder are observed by the device renderer check
    // (tests/android/TabChecks.kt, instrumentation argument `tabs`), not by this deterministic lane.
    val tabLabels = listOf("first", "second")
    val firstPanel = checkNotNull(TabPanels.select(tabLabels, 0.0, tabChildren))
    val secondPanel = checkNotNull(TabPanels.select(tabLabels, 1.0, tabChildren))
    check(firstPanel.index == 0 && firstPanel.node == firstTab) { "selection 0 renders the first child" }
    check(secondPanel.index == 1 && secondPanel.node == secondTab) { "selection 1 renders the second child" }
    val tabIdentities = TabPanels.identities(tabChildren)
    check(firstPanel.identity == tabIdentities[0]) { "the panel keeps the first child's identity" }
    check(secondPanel.identity == tabIdentities[1]) { "the panel keeps the second child's identity" }
    check(TabPanels.select(tabLabels, -1.0, tabChildren) == null) { "a negative selection renders no panel" }
    check(TabPanels.select(tabLabels, 0.5, tabChildren) == null) { "a non-integral selection renders no panel" }
    check(TabPanels.select(tabLabels, Double.NaN, tabChildren) == null) { "a nonfinite selection renders no panel" }
    check(TabPanels.select(listOf("first"), 0.0, tabChildren) == null) { "a missing label renders no panel" }
    check(TabPanels.select(listOf("first", "second", "third"), 0.0, tabChildren) == null) { "an extra label renders no panel" }
    // Retention identity: the child's own node identity, never the selection or the panel index, so
    // two panels share no state and a returned panel is restored under the identity it saved.
    check(tabIdentities[0] != tabIdentities[1]) { "two panels share no state" }
    check(TabPanels.identities(listOf(firstTab.copy(props = emptyList(), children = emptyList()), secondTab)) == tabIdentities) {
        "a panel identity ignores props and children"
    }
    check(TabPanels.identities(listOf(firstTab, secondTab))[0] ==
        TabPanels.identities(listOf(secondTab, firstTab))[1]) {
        "with distinct carried identities, a panel identity ignores the panel index"
    }
    check(TabPanels.identities(listOf(node("Column").copy(key = "first"), node("Column").copy(key = "second"))) == tabIdentities) {
        "two panels with identical content and different identities share no state"
    }
    check(TabPanels.identities(listOf(firstTab, firstTab)).toSet().size == 2) {
        "siblings that carry one identity do not share state"
    }
    check(TabPanels.identities(listOf(node("Column"), node("Column"))).toSet().size == 2) {
        "children that carry no key do not share state"
    }

    // Draft presentation: a retentive draft applies only while its authoritative identity is unchanged.
    check(DraftText.shown(Draft("typed", "0.5"), "0.5", "0.5") == "typed") { "a returned panel keeps the draft typed against the same value" }
    check(DraftText.shown(Draft("typed", "0.5"), "0.50", "0.50") == "0.50") { "a changed authoritative identity wins over the stale draft" }
    check(DraftText.shown(Draft("typed", "2.4"), "2.4", "2") == "typed") { "the value identity, not the rounded text, decides the draft" }
    check(DraftText.identity(2.4) != DraftText.identity(2.49)) {
        "the draft identity separates values that render the same text"
    }
    check(NumericPresentation.format(2.4, "", "", 1, 0) == NumericPresentation.format(2.49, "", "", 1, 0)) {
        "the rendered text alone cannot separate them"
    }
    check(DraftText.reconciled(Draft("typed", "0.5"), "0.50", "0.50") == Draft("0.50", "0.50")) {
        "a superseded draft is replaced by the authoritative presentation"
    }
    check(DraftText.reconciled(Draft("typed", "0.5"), "0.5", "1") == Draft("typed", "0.5")) { "a live draft is kept" }
    // Native Timer presentation: millis is a Unix epoch deadline (down) or origin, compared with
    // the presentation clock; the distance is displayed, never the epoch itself.
    val timerEpoch = 1_700_000_000_000.0
    check(TimerPresentation.text(timerEpoch, down = true, clockMillis = (timerEpoch - 65_000).toLong()) == "1:05 left")
    check(TimerPresentation.text(timerEpoch, down = false, clockMillis = (timerEpoch + 65_000).toLong()) == "1:05 ago")
    // A deadline reaches zero at the deadline, never before; an origin counts from zero at the origin.
    fun deadline(offsetMillis: Long) = TimerPresentation.text(timerEpoch, down = true, clockMillis = (timerEpoch + offsetMillis).toLong())
    fun origin(offsetMillis: Long) = TimerPresentation.text(timerEpoch, down = false, clockMillis = (timerEpoch + offsetMillis).toLong())
    check(deadline(-2_000) == "0:02 left")
    check(deadline(-1_001) == "0:02 left")
    check(deadline(-1_000) == "0:01 left")
    check(deadline(-1) == "0:01 left")
    check(deadline(0) == "0:00 left")
    check(deadline(1) == "0:00 left")
    check(origin(-1_000) == "0:00 ago")
    check(origin(0) == "0:00 ago")
    check(origin(999) == "0:00 ago")
    check(origin(1_000) == "0:01 ago")
    check(origin(1_999) == "0:01 ago")
    check(origin(2_000) == "0:02 ago")
    check(origin(3_661_000) == "1:01:01 ago")
    check(deadline(-3_661_000) == "1:01:01 left")
    // Visible progress recomputes from the clock and stops ticking once a deadline is reached.
    check(TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = (timerEpoch - 5_000).toLong()) == 1_000L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = (timerEpoch - 1_500).toLong()) == 500L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = (timerEpoch - 1).toLong()) == 1L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = (timerEpoch).toLong()) == null)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = (timerEpoch + 1).toLong()) == null)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = false, clockMillis = (timerEpoch + 1_500).toLong()) == 500L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = false, clockMillis = (timerEpoch + 2_000).toLong()) == 1_000L)
    // The visible tick loop advances one second at a time and releases itself at the deadline.
    var visibleClock = (timerEpoch - 5_000).toLong()
    val visible = mutableListOf<String>()
    while (true) {
        visible.add(TimerPresentation.text(timerEpoch, down = true, clockMillis = visibleClock))
        val wait = TimerPresentation.nextTickMillis(timerEpoch, down = true, clockMillis = visibleClock) ?: break
        visibleClock += wait
    }
    check(visible == listOf("0:05 left", "0:04 left", "0:03 left", "0:02 left", "0:01 left", "0:00 left")) {
        "visible progress drifted: $visible"
    }
    check(visibleClock == timerEpoch.toLong()) { "the countdown stopped at the deadline, got ${visibleClock - timerEpoch.toLong()}ms" }
    // An origin later than the clock shows no elapsed time and wakes when the display can change.
    check(TimerPresentation.text(timerEpoch, down = false, clockMillis = (timerEpoch - 500).toLong()) == "0:00 ago")
    check(TimerPresentation.nextTickMillis(timerEpoch, down = false, clockMillis = (timerEpoch - 500).toLong()) == 1_500L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = false, clockMillis = (timerEpoch - 1).toLong()) == 1_001L)
    check(TimerPresentation.nextTickMillis(timerEpoch, down = false, clockMillis = timerEpoch.toLong()) == 1_000L)
    // A sub-millisecond remainder still schedules a positive wait instead of spinning on zero,
    // and a deadline that close still shows a whole second so it never reaches zero early.
    check(TimerPresentation.text(timerEpoch + 0.4, down = true, clockMillis = timerEpoch.toLong()) == "0:01 left")
    check(TimerPresentation.nextTickMillis(timerEpoch + 0.4, down = true, clockMillis = timerEpoch.toLong()) == 1L)
    // A fractional distance wakes at the boundary its own displayed second uses, and the remainder
    // is rounded up so the wake never lands a millisecond before the second it announces: 1000.5ms
    // away still shows "0:02 left" and wakes once, at the second it drops to "0:01".
    check(TimerPresentation.text(timerEpoch + 1_000.5, down = true, clockMillis = timerEpoch.toLong()) == "0:02 left")
    check(TimerPresentation.nextTickMillis(timerEpoch + 1_000.5, down = true, clockMillis = timerEpoch.toLong()) == 1L)
    check(TimerPresentation.text(timerEpoch + 999.5, down = true, clockMillis = timerEpoch.toLong()) == "0:01 left")
    // A remainder beyond a whole second rounds up too: 2500.7ms away wakes after 501ms on the second
    // that turns "0:03" into "0:02", instead of a millisecond early on an unchanged reading.
    check(TimerPresentation.text(timerEpoch + 2_500.7, down = true, clockMillis = timerEpoch.toLong()) == "0:03 left")
    check(TimerPresentation.nextTickMillis(timerEpoch + 2_500.7, down = true, clockMillis = timerEpoch.toLong()) == 501L)
    check(TimerPresentation.text(timerEpoch + 2_500.7, down = true, clockMillis = timerEpoch.toLong() + 501L) == "0:02 left")
    // The same rounding in the origin direction, where truncating the remainder would wake a
    // millisecond early on the reading it already shows.
    check(TimerPresentation.text(timerEpoch + 0.5, down = false, clockMillis = timerEpoch.toLong() + 1_500) == "0:01 ago")
    check(TimerPresentation.nextTickMillis(timerEpoch + 0.5, down = false, clockMillis = timerEpoch.toLong() + 1_500) == 501L)
    check(TimerPresentation.text(timerEpoch + 0.5, down = false, clockMillis = timerEpoch.toLong() + 2_001) == "0:02 ago")
    // A distant future origin re-reads the clock within a bounded span instead of waiting unbounded
    // and instead of spinning on a millisecond: the wait clears the 1ms floor and never overruns the
    // boundary it waits for.
    check(TimerPresentation.nextTickMillis(timerEpoch + 172_800_000.0, down = false, clockMillis = timerEpoch.toLong()) in 1_001L..172_800_000L) {
        "a far-future origin neither spins nor waits past the second its display changes"
    }
    // A deadline always waits for its next displayed boundary, so even an epoch thirty-one thousand
    // years out waits one second: the re-read bound belongs to origins, never to a running countdown.
    check(TimerPresentation.nextTickMillis(1e15, down = true, clockMillis = 0L) == 1_000L)
    check(TimerPresentation.nextTickMillis(1e300, down = false, clockMillis = 0L) == null)
    // Signed finite epochs are valid: a pre-1970 origin is a real distance from the clock.
    check(TimerPresentation.text(-86_400_000.0, down = false, clockMillis = 0L) == "24:00:00 ago")
    check(TimerPresentation.text(-86_400_000.0, down = true, clockMillis = 0L) == "0:00 left")
    check(TimerPresentation.nextTickMillis(-86_400_000.0, down = true, clockMillis = 0L) == null)
    // A finite but extreme epoch still paints a whole unsigned duration instead of overflowing or
    // printing exponent notation; how long that duration is remains the presentation's own choice.
    fun durationText(text: String, suffix: String): Boolean {
        if (!text.endsWith(" $suffix")) return false
        val parts = text.removeSuffix(" $suffix").split(":")
        if (parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return false
        return parts.size == 2 || (parts.size == 3 && parts[1].length == 2 && parts[2].length == 2)
    }
    check(durationText(TimerPresentation.text(1e300, down = true, clockMillis = 0L), "left")) {
        "an extreme deadline epoch is not painted as a duration: '${TimerPresentation.text(1e300, down = true, clockMillis = 0L)}'"
    }
    check(durationText(TimerPresentation.text(-1e300, down = false, clockMillis = 0L), "ago")) {
        "an extreme origin epoch is not painted as a duration: '${TimerPresentation.text(-1e300, down = false, clockMillis = 0L)}'"
    }
    check(TimerPresentation.nextTickMillis(1e300, down = true, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(-1e300, down = false, clockMillis = 0L) == null)
    // Ingress rejects a nonfinite epoch first; an unvalidated one still reads as no distance in
    // either direction and ticks not at all, rather than crashing, printing a sentinel duration or
    // spinning the tick loop on a zero wait.
    check(TimerPresentation.text(Double.NaN, down = true, clockMillis = 0L) == "0:00 left")
    check(TimerPresentation.text(Double.NaN, down = false, clockMillis = 0L) == "0:00 ago")
    check(TimerPresentation.text(Double.POSITIVE_INFINITY, down = true, clockMillis = 0L) == "0:00 left")
    check(TimerPresentation.text(Double.POSITIVE_INFINITY, down = false, clockMillis = 0L) == "0:00 ago")
    check(TimerPresentation.text(Double.NEGATIVE_INFINITY, down = true, clockMillis = 0L) == "0:00 left")
    check(TimerPresentation.text(Double.NEGATIVE_INFINITY, down = false, clockMillis = 0L) == "0:00 ago")
    check(TimerPresentation.nextTickMillis(Double.NaN, down = true, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(Double.NaN, down = false, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(Double.POSITIVE_INFINITY, down = true, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(Double.POSITIVE_INFINITY, down = false, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(Double.NEGATIVE_INFINITY, down = true, clockMillis = 0L) == null)
    check(TimerPresentation.nextTickMillis(Double.NEGATIVE_INFINITY, down = false, clockMillis = 0L) == null)

    // Dispatch completeness and the root wrapper mapping.
    accepted(node("root", children = listOf(node("Text", listOf(text("text", "x"))))))
    accepted(node("__root", children = listOf(node("Text", listOf(text("text", "x"))))))
    rejected(node("Mystery"), "PROP_FAULT", "Mystery")

    // Display vocabulary: exactly the contract set, and verification rejects any retained legacy row.
    val expected = mapOf(
        "Text" to setOf("text"), "NumberText" to setOf("prefix", "suffix"), "NumberStat" to setOf("prefix", "suffix"),
        "NumberListItem" to setOf("prefix", "suffix"), "Section" to setOf("title", "subtitle"),
        "InsetBanner" to setOf("title", "message", "actionText"), "EmptyState" to setOf("title", "message", "actionText"),
        "Snackbar" to setOf("message", "actionText"), "Button" to setOf("text"), "Cell" to setOf("text"),
        "Toggle" to setOf("label"), "Checkbox" to setOf("label"),
    )
    check(DisplayVocabulary.table == expected) { "Display vocabulary drifted: ${DisplayVocabulary.table}" }
    RenderComponent.verifyDisplay(pack, expected)
    expectMismatch({ RenderComponent.verifyDisplay(pack, mapOf("Heading" to setOf("value"))) }, "legacy display row")
    expectMismatch({ RenderComponent.verifyDisplay(pack, mapOf("Section" to setOf("summary"))) }, "legacy display prop")
    expectMismatch({ RenderComponent.verifyDisplay(pack, mapOf("Text" to setOf("text", "colour"))) }, "added display row")

    // Presentation helper: every displayable string is transformed, everything else is copied
    // unchanged, the input tree is never mutated, and no row is missing from the fixture.
    val sample = UiNode("root", emptyList(), listOf(
        UiNode("Text", listOf(text("text", "body text"), text("style", "body")), emptyList()),
        UiNode("NumberText", listOf(number("value", 32.0), text("prefix", "$"), text("suffix", " kg"),
            number("minimumDigits", 2.0), text("style", "metric")), emptyList()),
        UiNode("NumberStat", listOf(number("value", 1.0), text("prefix", "a"), text("suffix", "b"), text("label", "kept")), emptyList()),
        UiNode("NumberListItem", listOf(number("value", 2.0), text("prefix", "c"), text("suffix", "d")), emptyList()),
        UiNode("Section", listOf(text("title", "title"), text("subtitle", "subtitle"), text("style", "content")),
            listOf(UiNode("Text", listOf(text("text", "child")), emptyList(), key = "child-key"))),
        UiNode("InsetBanner", listOf(text("title", "t"), text("message", "m"), text("actionText", "x"), action("onAction")), emptyList()),
        UiNode("EmptyState", listOf(text("title", "t2"), text("message", "m2"), text("actionText", "x2"), action("onAction")), emptyList()),
        UiNode("Snackbar", listOf(text("message", "m3"), text("actionText", "x3"), action("onAction")), emptyList()),
        UiNode("Button", listOf(text("text", "tap"), number("value", 7.0), flag("enabled", true), action("onClick"), text("accessibilityLabel", "kept")), emptyList()),
        UiNode("Cell", listOf(text("text", "cell"), number("value", 8.0), action("onClick")), emptyList()),
        UiNode("Toggle", listOf(text("label", "on?"), flag("checked", true), action("onChange")), emptyList()),
        UiNode("Checkbox", listOf(text("label", "yes?"), flag("checked", false), action("onChange")), emptyList()),
        UiNode("TextField", listOf(text("label", "Name"), text("value", "kept input"), number("keyboardType", 1.0), action("onChange")), emptyList()),
        UiNode("Image", listOf(text("source", "kept"), text("description", "kept")), emptyList()),
    ), key = "root-key")
    val snapshot = sample.toString()
    val transformed = displayText(sample) { "<$it>" }
    check(sample.toString() == snapshot) { "the presentation helper must not mutate the input tree" }
    val visited = linkedSetOf<String>()
    fun checkNode(source: UiNode, output: UiNode) {
        check(output.component == source.component && output.key == source.key) { "${source.component} identity changed" }
        check(output.props.size == source.props.size) { "${source.component} props changed count" }
        val displayable = DisplayVocabulary.table[source.component] ?: emptySet()
        for ((index, prop) in source.props.withIndex()) {
            val result = output.props[index]
            check(result.name == prop.name) { "${source.component} prop order changed" }
            val value = prop.value
            if (prop.name in displayable && value is UiValue.Text) {
                check(result.value == UiValue.Text("<${value.value}>")) { "${source.component}.${prop.name} was not transformed" }
                visited.add("${source.component}.${prop.name}")
            } else {
                check(result == prop) { "${source.component}.${prop.name} must be copied unchanged" }
            }
        }
        check(output.children.size == source.children.size) { "${source.component} children changed count" }
        for (index in source.children.indices) checkNode(source.children[index], output.children[index])
    }
    checkNode(sample, transformed)
    val expectedDisplay = expected.entries.flatMap { (component, names) -> names.map { "$component.$it" } }.toSet()
    check(visited == expectedDisplay) { "the fixture must carry every displayable field: ${expectedDisplay - visited}" }

    // Prop declaration agreement: a changed or added declaration rejects naming the component and prop.
    val rows = PackPropDeclarations.rows
    val changedKind = rows.map { if (it.component == "Button" && it.prop == "value") it.copy(kind = "string") else it }
    expectMismatch({ RenderComponent.verify(pack, changedKind, expected) }, "changed declaration kind")
    val dropped = rows.filterNot { it.component == "Progress" && it.prop == "maximum" }
    expectMismatch({ RenderComponent.verify(pack, dropped, expected) }, "missing declaration")
    val added = rows + PackPropDeclarations.Row("Text", "colour", "string", false, false, null)
    expectMismatch({ RenderComponent.verify(pack, added, expected) }, "added declaration")
    val changedDefault = rows.map { if (it.component == "Tile" && it.prop == "span") it.copy(default = PropDefault.Num(2.0)) else it }
    expectMismatch({ RenderComponent.verify(pack, changedDefault, expected) }, "changed declaration default")

    // Compile input: absent or out-of-set targets are never supplied, derived or promoted.
    check(CompileInput.target(null) == null)
    check(CompileInput.target("print") == null)
    check(CompileInput.target("screen") == "screen")
    check(CompileInput.target("chat") == "chat")
    check(CompileInput.target("card") == "card")

    println("renderer rules: metadata, resolution, seven classes, presentation, style, payloads, graphics, card, dispatch, tabs panels, drafts and display vocabulary passed")
}
