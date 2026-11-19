package dev.deal.embedding

import deal.ui.UiModel

/** One entry per pack component. The capability is derived from the component name. */
internal enum class RenderComponent(val component: String) {
    ActionBar("ActionBar"),
    AdaptiveColumns("AdaptiveColumns"),
    AppTheme("AppTheme"),
    BackHandler("BackHandler"),
    Badge("Badge"),
    Button("Button"),
    Canvas("Canvas"),
    Card("Card"),
    Cell("Cell"),
    Checkbox("Checkbox"),
    ClockText("ClockText"),
    Column("Column"),
    DateField("DateField"),
    Divider("Divider"),
    EmptyState("EmptyState"),
    Grid("Grid"),
    Header("Header"),
    Hero("Hero"),
    Icon("Icon"),
    IconButton("IconButton"),
    Image("Image"),
    InsetBanner("InsetBanner"),
    KeyValueGroup("KeyValueGroup"),
    KeyValueItem("KeyValueItem"),
    Layers("Layers"),
    ListGroup("ListGroup"),
    ListItem("ListItem"),
    MetricGroup("MetricGroup"),
    Motion("Motion"),
    NumberField("NumberField"),
    NumberListItem("NumberListItem"),
    NumberStat("NumberStat"),
    NumberText("NumberText"),
    Path("Path"),
    Progress("Progress"),
    ProgressRing("ProgressRing"),
    Row("Row"),
    Scroll("Scroll"),
    Section("Section"),
    SectionHeader("SectionHeader"),
    Select("Select"),
    Skeleton("Skeleton"),
    Slider("Slider"),
    Snackbar("Snackbar"),
    Spacer("Spacer"),
    Sparkline("Sparkline"),
    Spinner("Spinner"),
    Stage("Stage"),
    Stat("Stat"),
    Surface("Surface"),
    Table("Table"),
    TableRow("TableRow"),
    Tabs("Tabs"),
    Text("Text"),
    TextField("TextField"),
    Ticker("Ticker"),
    Tile("Tile"),
    TileGrid("TileGrid"),
    TimeField("TimeField"),
    Timeline("Timeline"),
    TimelineItem("TimelineItem"),
    Timer("Timer"),
    Toggle("Toggle"),
    Transform("Transform");
    val capability: String get() = "renderer.$component"

    companion object {
        fun entry(name: String): RenderComponent? = entries.firstOrNull { it.component == name }

        /** Verify the entry set, capabilities, prop declarations, projections and display table against the pack. */
        fun verify(pack: UiModel.PackModule) = verify(pack, PackPropDeclarations.rows, DisplayVocabulary.table)

        /** Verifies prepared declarations and a display table; the defaults are the shipped projections. */
        fun verify(pack: UiModel.PackModule, rows: List<PackPropDeclarations.Row>, display: Map<String, Set<String>>) {
            val declared = pack.components()
            for (entry in entries) {
                val component = declared[entry.component]
                    ?: throw IllegalStateException("$PACK_MISMATCH capability ${entry.component}: pack declares no such component")
                val capabilities = component.contracts().filterIsInstance<UiModel.Capability>().map { it.name() }
                if (capabilities.size != 1 || capabilities[0] != entry.capability) {
                    throw IllegalStateException("$PACK_MISMATCH capability ${entry.component}: pack declares $capabilities, entry requires ${entry.capability}")
                }
                verifyDeclarations(pack, entry.component, rows.filter { it.component == entry.component })
                val events = component.contracts().filterIsInstance<UiModel.Event>().associate { it.prop() to payloadKind(it) }
                val expected = RendererEvents.payloads[entry.component] ?: emptyMap()
                if (events != expected) {
                    throw IllegalStateException("$PACK_MISMATCH event ${entry.component}: pack declares $events, metadata reconstructs $expected")
                }
            }
            for (name in declared.keys) {
                if (entries.none { it.component == name }) {
                    throw IllegalStateException("$PACK_MISMATCH component $name: no renderer entry or dispatch branch")
                }
            }
            for ((identity, constraint) in PackConstraints.classes) {
                val component = identity.substringBeforeLast('.')
                val prop = identity.substringAfterLast('.')
                val field = declared[component]?.let { pack.classes()[it.propsType()] }?.fields()?.firstOrNull { it.name() == prop }
                    ?: throw IllegalStateException("$PACK_MISMATCH constraint $identity: pack declares no such prop")
                val kind = field.type().name() + if (field.type().array()) "[]" else ""
                val compatible = when (constraint) {
                    "pose" -> kind == "number[]"
                    "finite" -> kind == "number" || kind == "number[]"
                    else -> kind == "number"
                }
                if (!compatible) throw IllegalStateException("$PACK_MISMATCH constraint $identity: $constraint requires a compatible declared type, found $kind")
            }
            for (name in PackCardProfile.components) {
                if (name !in declared) throw IllegalStateException("$PACK_MISMATCH card $name: profile component is not declared by the pack")
            }
            for (event in PackCardProfile.events) {
                val component = event.substringBeforeLast('.')
                val prop = event.substringAfterLast('.')
                val events = declared[component]?.contracts()?.filterIsInstance<UiModel.Event>()?.map { it.prop() } ?: emptyList()
                if (prop !in events) throw IllegalStateException("$PACK_MISMATCH card $event: card event is not declared by the pack")
            }
            verifyDisplay(pack, display)
        }

        fun verifyDisplay(pack: UiModel.PackModule, display: Map<String, Set<String>>) {
            val declared = pack.components()
            for ((component, names) in display) {
                val cls = declared[component]?.let { pack.classes()[it.propsType()] }
                    ?: throw IllegalStateException("$PACK_MISMATCH display $component: displayable component is not declared by the pack")
                for (name in names) {
                    if (cls.fields().none { it.name() == name }) {
                        throw IllegalStateException("$PACK_MISMATCH display $component.$name: displayable prop is not declared by the pack")
                    }
                }
            }
        }

        private fun verifyDeclarations(pack: UiModel.PackModule, name: String, rows: List<PackPropDeclarations.Row>) {
            val cls = pack.classes()[pack.components().getValue(name).propsType()]
                ?: throw IllegalStateException("$PACK_MISMATCH prop $name: props class is not declared")
            if (rows.size != cls.fields().size) {
                throw IllegalStateException("$PACK_MISMATCH prop $name: declared ${cls.fields().size} props, metadata carries ${rows.size}")
            }
            for (row in rows) {
                val field = cls.fields().firstOrNull { it.name() == row.prop }
                    ?: throw IllegalStateException("$PACK_MISMATCH prop $name.${row.prop}: metadata declares a prop the pack does not")
                if (kindOf(field.type()) != row.kind) {
                    throw IllegalStateException("$PACK_MISMATCH prop $name.${row.prop}: kind ${row.kind} != ${kindOf(field.type())}")
                }
                if (field.type().optional() != row.optional) {
                    throw IllegalStateException("$PACK_MISMATCH optionality $name.${row.prop}: metadata disagrees with the pack")
                }
                if ((field.defaultValue() == null) != (row.default == null)) {
                    throw IllegalStateException("$PACK_MISMATCH default $name.${row.prop}: metadata disagrees with the pack")
                }
                if (field.defaultValue() != null && PropDeclarationFacts.defaultOf(field.defaultValue(), field.type()) != row.default) {
                    throw IllegalStateException("$PACK_MISMATCH default $name.${row.prop}: metadata disagrees with the pack")
                }
            }
        }

        private fun kindOf(type: UiModel.TypeRef): String = when {
            type.array() && type.name() == "number" -> "numbers"
            type.array() -> "json"
            type.name() == "Action" -> "action"
            type.name() == "string" -> "string"
            type.name() == "number" -> "number"
            type.name() == "boolean" -> "boolean"
            else -> "json"
        }

        private fun payloadKind(event: UiModel.Event): String = when (event.payload()?.name()) {
            null -> "none"
            "graphics.Pointer" -> "pointer"
            "string" -> "string"
            "number" -> "number"
            "boolean" -> "boolean"
            else -> "unsupported"
        }
    }
}

internal const val PACK_MISMATCH = "PACK_MISMATCH"

/** Declared payload kind per component event; verified against the pack at build and compilation. */
internal object RendererEvents {
    val payloads: Map<String, Map<String, String>> = mapOf(
        "ListItem" to mapOf("onTap" to "number"),
        "TimelineItem" to mapOf("onTap" to "number"),
        "NumberListItem" to mapOf("onTap" to "number"),
        "Button" to mapOf("onClick" to "number"),
        "Cell" to mapOf("onClick" to "number"),
        "IconButton" to mapOf("onClick" to "number"),
        "TextField" to mapOf("onChange" to "string"),
        "NumberField" to mapOf("onChange" to "number"),
        "TimeField" to mapOf("onChange" to "number"),
        "DateField" to mapOf("onChange" to "number"),
        "Toggle" to mapOf("onChange" to "boolean"),
        "Checkbox" to mapOf("onChange" to "boolean"),
        "Slider" to mapOf("onChange" to "number"),
        "Select" to mapOf("onChange" to "number"),
        "Tabs" to mapOf("onChange" to "number"),
        "InsetBanner" to mapOf("onAction" to "none"),
        "EmptyState" to mapOf("onAction" to "none"),
        "Snackbar" to mapOf("onAction" to "none"),
        "Ticker" to mapOf("onTick" to "number"),
        "BackHandler" to mapOf("onBack" to "none"),
        "Path" to mapOf("onTap" to "number"),
        "Surface" to mapOf("onFrame" to "number", "onPointer" to "pointer"),
    )
}

/** Only spec components and spec props are displayable strings. */
internal object DisplayVocabulary {
    val table: Map<String, Set<String>> = mapOf(
        "Section" to setOf("subtitle", "title"),
        "Text" to setOf("text"),
        "NumberText" to setOf("prefix", "suffix"),
        "NumberStat" to setOf("prefix", "suffix"),
        "NumberListItem" to setOf("prefix", "suffix"),
        "Button" to setOf("text"),
        "Cell" to setOf("text"),
        "Toggle" to setOf("label"),
        "Checkbox" to setOf("label"),
        "InsetBanner" to setOf("actionText", "message", "title"),
        "EmptyState" to setOf("actionText", "message", "title"),
        "Snackbar" to setOf("actionText", "message"),
    )
}
