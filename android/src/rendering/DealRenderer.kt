package dev.deal.embedding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import org.json.JSONObject

/** One entry per implemented component. Compilation verifies agreement with the pack. */
internal enum class RenderComponent(val component: String, val capability: String) {
    Column("Column", "renderer.compose.column"), Card("Card", "renderer.compose.card"),
    Text("Text", "renderer.compose.text"), Hero("Hero", "renderer.compose.hero"),
    Button("Button", "renderer.compose.button"), Toggle("Toggle", "renderer.compose.toggle"),
    IntText("IntText", "renderer.compose.integer"), Time("Time", "renderer.compose.time"),
    Spinner("Spinner", "renderer.compose.spinner"),
    Section("Section", "renderer.compose.section"), Option("Option", "renderer.compose.option"),
    IntField("IntField", "renderer.compose.intfield"), TextField("TextField", "renderer.compose.textfield"),
    SearchField("SearchField", "renderer.compose.searchfield"),
    Choice("Choice", "renderer.compose.choice"), Filters("Filters", "renderer.compose.filters"),
    Notice("Notice", "renderer.compose.notice"), Progress("Progress", "renderer.compose.progress"),
    Failure("Failure", "renderer.compose.failure"), Item("Item", "renderer.compose.item"),
    KeyedList("KeyedList", "renderer.compose.keyedlist"),
    Row("Row", "renderer.layout.row"), Heading("Heading", "renderer.text.heading"),
    Caption("Caption", "renderer.text.caption"), PrimaryAction("PrimaryAction", "renderer.action.primary"),
    SecondaryAction("SecondaryAction", "renderer.action.secondary"),
    Money("Money", "renderer.value.money"), Metric("Metric", "renderer.value.metric"),
    Comparison("Comparison", "renderer.value.comparison");

    companion object {
        fun verify(pack: deal.ui.UiModel.PackModule) {
            val declared = pack.components().mapValues { (_, c) -> c.contracts().filterIsInstance<deal.ui.UiModel.Capability>().map { it.name() }.single() }
            check(declared == entries.associate { it.component to it.capability }) { "Renderer and component pack disagree" }
        }
    }
}

/** Presentation only: amounts remain integer minor units and no domain status is inferred. */
internal fun formatMoney(minorUnits: Int, currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val unit = java.util.Currency.getInstance(currency)
    val amount = java.math.BigDecimal.valueOf(minorUnits.toLong(), unit.defaultFractionDigits.coerceAtLeast(0))
    return java.text.NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = unit
        minimumFractionDigits = unit.defaultFractionDigits.coerceAtLeast(0)
        maximumFractionDigits = minimumFractionDigits
    }.format(amount)
}

private val LocalComparisonValue = compositionLocalOf { false }

@Composable private fun LabeledValue(label: String, value: String) {
    val comparison = LocalComparisonValue.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        // Comparison rows share a label/value grid. At large text sizes stack
        // within each cell rather than squeezing either explicit label or value.
        if (comparison && maxWidth / fontScale >= 300.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Measure every checked child at an equal share; wrap at font-scaled minimum widths.
 * A Layout rather than a row/column branch preserves child composition identity on resize. */
@Composable private fun AdaptiveRow(minimumChildWidth: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val minimum = with(density) { minimumChildWidth.coerceAtLeast(48).dp.toPx() * density.fontScale.coerceAtLeast(1f) }.toInt()
    val gap = with(density) { 12.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { children, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else minimum
        val columns = ((width.toLong() + gap) / (minimum.toLong() + gap)).toInt().coerceAtLeast(1).coerceAtMost(children.size.coerceAtLeast(1))
        val cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val measured = children.map { it.measure(Constraints(minWidth = cell, maxWidth = cell)) }
        val rows = measured.chunked(columns)
        val height = rows.sumOf { row -> row.maxOf { it.height } } + gap * (rows.size - 1).coerceAtLeast(0)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            for (row in rows) {
                row.forEachIndexed { index, child -> child.placeRelative(index * (cell + gap), y) }
                y += row.maxOf { it.height } + gap
            }
        }
    }
}

/** Renders checked portable nodes with the same Compose quality as native app screens. */
@Composable fun DealRenderer(node: JSONObject, dispatch: (Int, String?) -> Unit) {
    val properties = node.getJSONArray("props")
    fun prop(name: String): JSONObject? = (0 until properties.length()).map { properties.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
    fun text(name: String) = prop(name)?.optString("stringValue") ?: ""
    fun flag(name: String, default: Boolean = false) = prop(name)?.optBoolean("booleanValue", default) ?: default
    fun number(name: String, default: Int = 0): Int {
        val value = prop(name)?.getDouble("numberValue") ?: return default
        require(value.isFinite() && value == kotlin.math.floor(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) { "Expected integral $name for ${node.getString("component")}" }
        return value.toInt()
    }
    fun choices(actions: List<String>): List<Pair<String, Int?>> =
        listOf("first", "second", "third").mapIndexed { index, name ->
            Pair(prop(name)?.optString("stringValue") ?: "", prop(actions[index])?.optInt("actionSlot"))
        }.filter { it.first.isNotEmpty() }
    @Composable fun children() {
        val nodes = node.getJSONArray("children")
        for (i in 0 until nodes.length()) {
            val child = nodes.getJSONObject(i)
            key(child.getString("structural"), child.getString("keyKind"), child.getDouble("keyNumber"), child.getString("keyString")) { DealRenderer(child, dispatch) }
        }
    }
    val name = node.getString("component").substringAfterLast('.')
    val component = if (name == "root" || name == "__root") RenderComponent.Column else RenderComponent.entries.single { it.component == name }
    when (component) {
        RenderComponent.Column -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { children() }
        RenderComponent.Row -> AdaptiveRow(number("minimumChildWidth", 240)) { children() }
        RenderComponent.Heading -> Text(text("value"), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        RenderComponent.Caption -> Text(text("value"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RenderComponent.Money -> LabeledValue(text("label"), formatMoney(number("minorUnits"), prop("currency")?.getString("stringValue") ?: "EUR"))
        RenderComponent.Metric -> LabeledValue(text("label"), listOf(text("value"), text("unit")).filter { it.isNotEmpty() }.joinToString(" "))
        RenderComponent.Comparison -> Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text("status"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                CompositionLocalProvider(LocalComparisonValue provides true) {
                    AdaptiveRow(320) { children() }
                }
            }
        }
        RenderComponent.Card -> Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { children() }
        }
        RenderComponent.Text -> Text(text("value"), style = MaterialTheme.typography.bodyLarge)
        RenderComponent.Hero -> Text(text("value"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        RenderComponent.Button, RenderComponent.PrimaryAction, RenderComponent.SecondaryAction -> {
            val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = text("accessibilityLabel").ifEmpty { text("text") }
            }
            val click = { prop("onClick")?.let { dispatch(it.getInt("actionSlot"), null) }; Unit }
            if (component == RenderComponent.SecondaryAction) {
                OutlinedButton(enabled = flag("enabled", true), onClick = click,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = text("accessibilityLabel").ifEmpty { text("text") } },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { Text(text("text")) }
            } else {
                Button(enabled = flag("enabled", true), onClick = click, modifier = actionModifier, contentPadding = PaddingValues(16.dp)) { Text(text("text")) }
            }
        }
        RenderComponent.Toggle -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text("text"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Checkbox(flag("checked"), { prop("onChange")?.let { dispatch(it.getInt("actionSlot"), text("value")) } }, modifier = Modifier.semantics { contentDescription = text("accessibilityLabel") })
        }
        RenderComponent.IntText -> Text(number("value").toString(), style = MaterialTheme.typography.headlineMedium)
        RenderComponent.Time -> { val minute = number("value"); Text("%02d:%02d".format(minute / 60, minute % 60), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary) }
        RenderComponent.Spinner -> CircularProgressIndicator()
        RenderComponent.Section -> {
            var open by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (text("summary").isNotEmpty()) Text(text("summary"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand") }
                }
                if (open) children()
            }
        }
        RenderComponent.Option -> {
            val selected = flag("selected")
            val enabled = flag("enabled", true)
            Surface(
                onClick = { if (enabled) prop("onSelect")?.let { dispatch(it.getInt("actionSlot"), text("value")) } },
                enabled = enabled,
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = text("accessibilityLabel") },
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (text("detail").isNotEmpty()) Text(text("detail"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (text("meta").isNotEmpty()) Text(text("meta"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val nested = node.getJSONArray("children")
                        if (nested.length() > 0) children()
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (text("price").isNotEmpty()) Text(text("price"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (selected) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        RenderComponent.IntField -> {
            val value = number("value")
            val lower = number("minimum"); val upper = number("maximum", 999); val step = number("step", 1).coerceAtLeast(1)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
              Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text("label"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { prop("onChange")?.let { dispatch(it.getInt("actionSlot"), (value.toLong() - step).coerceIn(lower.toLong(), upper.coerceAtLeast(lower).toLong()).toString()) } }, enabled = flag("enabled", true) && lower <= upper && value > lower,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = text("accessibilityLabel").ifEmpty { text("label") } + " decrease" }) { Text("−", fontWeight = FontWeight.Bold) }
                    Text(if (text("format") == "euro-cents") formatMoney(value, "EUR") else value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { prop("onChange")?.let { dispatch(it.getInt("actionSlot"), (value.toLong() + step).coerceIn(lower.toLong(), upper.coerceAtLeast(lower).toLong()).toString()) } }, enabled = flag("enabled", true) && lower <= upper && value < upper,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = text("accessibilityLabel").ifEmpty { text("label") } + " increase" }) { Text("+", fontWeight = FontWeight.Bold) }
                }
                val hint = text("error").ifEmpty { text("supporting") }
                if (hint.isNotEmpty()) Text(hint, color = if (text("error").isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
              }
            }
        }
        RenderComponent.TextField, RenderComponent.SearchField -> {
            val multiline = flag("multiline")
            val authoritative = text("value")
            var draft by remember { mutableStateOf(authoritative) }
            val pending = remember { mutableListOf<String>() }
            // Keep IME edits responsive while the serialized DEAL worker acknowledges them.
            // This is transport buffering, not application state or validation policy.
            LaunchedEffect(authoritative) {
                val acknowledged = pending.indexOf(authoritative)
                if (acknowledged >= 0) {
                    repeat(acknowledged + 1) { pending.removeAt(0) }
                    if (pending.isEmpty()) draft = authoritative
                } else { pending.clear(); draft = authoritative }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { next -> prop("onChange")?.let { action -> draft = next; pending.add(next); dispatch(action.getInt("actionSlot"), next) } },
                label = { Text(text("label")) },
                enabled = flag("enabled", true),
                isError = text("error").isNotEmpty(),
                supportingText = { val hint = text("error").ifEmpty { text("supporting") }; if (hint.isNotEmpty()) Text(hint) },
                singleLine = !multiline,
                minLines = if (multiline) 2 else 1,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = text("accessibilityLabel") },
                shape = RoundedCornerShape(16.dp),
            )
        }
        RenderComponent.Choice -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (text("label").isNotEmpty()) Text(text("label"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices(listOf("onFirst", "onSecond", "onThird")).forEach { (option, slot) ->
                    FilterChip(selected = option == text("value"), onClick = { slot?.let { dispatch(it, option) } }, label = { Text(option) })
                }
            }
        }
        RenderComponent.Filters -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices(listOf("onFirst", "onSecond", "onThird")).forEach { (option, slot) ->
                FilterChip(selected = option == text("selected"), onClick = { slot?.let { dispatch(it, option) } }, label = { Text(option) })
            }
        }
        RenderComponent.Notice -> {
            val tone = text("tone")
            val color = when (tone) { "error" -> MaterialTheme.colorScheme.error; "warning" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
            Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = .12f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tone == "info") Icons.Filled.Info else Icons.Filled.Warning, null, tint = color)
                    Text(text("text"), style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
        }
        RenderComponent.Progress -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(text("label").ifEmpty { "Loading" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RenderComponent.Failure -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text("text"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { prop("onRetry")?.let { dispatch(it.getInt("actionSlot"), null) } }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Try again") }
                }
            }
        }
        RenderComponent.Item -> Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text("title"), style = MaterialTheme.typography.bodyLarge)
                    if (text("detail").isNotEmpty()) Text(text("detail"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val nested = node.getJSONArray("children")
                    if (nested.length() > 0) children()
                }
                if (text("meta").isNotEmpty()) Text(text("meta"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (text("trailing").isNotEmpty()) Text(text("trailing"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        RenderComponent.KeyedList -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { children() } }
    }
}
