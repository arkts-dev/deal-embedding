package dev.deal.embedding

import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Resolved prop values; painters never read the pack or coerce a value. */
private class Values(private val map: Map<String, UiValue>) {
    fun text(name: String): String = (map[name] as? UiValue.Text)?.value ?: ""
    fun number(name: String, fallback: Double = 0.0): Double = (map[name] as? UiValue.Num)?.value ?: fallback
    fun flag(name: String, fallback: Boolean = false): Boolean = (map[name] as? UiValue.Flag)?.value ?: fallback
    fun slot(name: String): Int? = (map[name] as? UiValue.Slot)?.value
    fun json(name: String): JsonValue? = (map[name] as? UiValue.Json)?.value
    fun numbers(name: String): List<Double> = ((json(name) as? JsonValue.Arr)?.items ?: emptyList())
        .mapNotNull { (it as? JsonValue.Num)?.value }
    fun strings(name: String): List<String> = ((json(name) as? JsonValue.Arr)?.items ?: emptyList())
        .mapNotNull { (it as? JsonValue.Str)?.value }
    val all: Map<String, UiValue> get() = map
}

private val LocalFramed = compositionLocalOf { false }
private val LocalCardTarget = compositionLocalOf { false }

/** Host-configured containment bounds for renderer resources; the defaults apply outside a host surface. */
internal val LocalExecutionLimits = staticCompositionLocalOf { ExecutionLimits() }

/**
 * A rendered Image: the committed `src` is resolved through the one transport rule and its content
 * drawn, with `alt` kept as the accessible description in every state. Loading and failure are
 * explicit states, and the owned request is replaced by a new src, cancelled when the node leaves
 * the composition and disposed with its composition, so an obsolete completion never replaces the
 * current picture.
 */
@Composable private fun ImageContent(src: String, alt: String) {
    val limits = LocalExecutionLimits.current
    var revision by remember { mutableStateOf(0) }
    val load = remember(limits) { imageLoad(limits) { revision++ } }
    LaunchedEffect(load, src, alt) { load.show(src, alt) }
    DisposableEffect(load) { onDispose { load.dispose() } }
    // Reading the revision subscribes this painter to owned state changes on the delivery lane.
    @Suppress("UNUSED_VARIABLE") val applied = revision
    val painting = load.painting
    // A classified failure is reported once, so a host misconfiguration is diagnosable, and the node
    // still shows its explicit failure state with the alt text.
    LaunchedEffect(painting) {
        if (painting is ImagePainting.Failed) android.util.Log.w("DealRenderer", "Image unavailable: ${painting.code}")
    }
    when (painting) {
        is ImagePainting.Content<*> -> {
            val picture = painting.resource as android.graphics.Bitmap
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = alt.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height.coerceAtLeast(1)),
            )
        }
        is ImagePainting.Failed -> Column(Modifier.fillMaxWidth()) {
            if (alt.isNotEmpty()) Text(alt, style = PackTheme.caption)
            Text("Image unavailable", style = PackTheme.caption, color = PackTheme.muted)
        }
        is ImagePainting.Loading -> Box(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .background(PackTheme.muted.copy(alpha = .15f), RoundedCornerShape(12.dp))
                .semantics { contentDescription = alt }
        ) { CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp) }
        is ImagePainting.Blank -> if (alt.isNotEmpty()) Box(Modifier.semantics { contentDescription = alt })
    }
}

/** Saves a typed draft together with the authoritative value identity it was typed against. */
private val DraftSaver = listSaver<Draft, String>(
    save = { listOf(it.text, it.origin) },
    restore = { if (it.size == 2) Draft(it[0], it[1]) else null },
)

/**
 * A draft that outlives the composition which typed it and is replaced once the authoritative value
 * identity it was typed against changes, so a returning panel never revives superseded input.
 */
@Composable private fun draftOf(identity: String, authoritative: String): MutableState<Draft> {
    val state = rememberSaveable(identity, stateSaver = DraftSaver) { mutableStateOf(Draft(authoritative, identity)) }
    SideEffect { state.value = DraftText.reconciled(state.value, identity, authoritative) }
    return state
}

@Composable private fun PackIcon(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if (name == "minus") { Text("\u2212", modifier = modifier, color = tint); return }
    val vector: ImageVector = when (name) {
        "check" -> Icons.Filled.Check
        "close" -> Icons.Filled.Close
        "plus" -> Icons.Filled.Add
        "chevron-right" -> Icons.Filled.KeyboardArrowRight
        "navigate" -> Icons.Filled.ArrowForward
        "pin" -> Icons.Filled.Place
        "calendar" -> Icons.Filled.DateRange
        "clock" -> Icons.Filled.KeyboardArrowRight
        "star" -> Icons.Filled.Star
        "user" -> Icons.Filled.Person
        "warning" -> Icons.Filled.Warning
        "info" -> Icons.Filled.Info
        "trash" -> Icons.Filled.Delete
        "list" -> Icons.Filled.List
        else -> Icons.Filled.Star
    }
    Icon(vector, contentDescription = null, modifier = modifier, tint = tint)
}

@Composable private fun LabeledValue(label: String, value: String, supporting: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (supporting.isNotEmpty()) Text(supporting, style = MaterialTheme.typography.bodySmall)
    }
}

/** Measure every child at an equal share; wrap at font-scaled minimum widths. */
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

@Composable private fun ProgressBar(fraction: Double) {
    val background = PackTheme.muted.copy(alpha = .25f)
    val accent = PackTheme.accent
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRoundRect(accent, size = androidx.compose.ui.geometry.Size(size.width * fraction.toFloat(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

@Composable private fun ProgressDial(fraction: Double) {
    val background = PackTheme.muted.copy(alpha = .25f)
    val accent = PackTheme.accent
    Canvas(Modifier.size(48.dp)) {
        val stroke = Stroke(width = 6.dp.toPx())
        drawArc(background, 0f, 360f, false, style = stroke)
        drawArc(accent, -90f, 360f * fraction.toFloat(), false, style = stroke)
    }
}

@Composable private fun SparklinePicture(series: List<Double>, second: List<Double>, maximum: Double, minimum: Double) {
    val accent = PackTheme.accent
    val muted = PackTheme.muted
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        fun plot(points: List<Double>, color: Color) {
            if (points.isEmpty() || maximum <= minimum) return
            val path = Path()
            points.forEachIndexed { index, value ->
                val x = size.width * index / (points.size - 1).coerceAtLeast(1)
                val y = size.height * (1f - ((value - minimum) / (maximum - minimum)).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        plot(series, accent)
        plot(second, muted)
    }
}

/**
 * Native Timer: the epoch deadline or origin compared with the presentation clock while visible.
 * Ticking owns only this node's presentation: it changes no AppState, dispatches no action and
 * performs no business completion. Hiding or leaving the composition cancels the tick loop, and
 * the next visible tick recomputes from the current clock instead of replaying hidden time.
 */
@Composable private fun NativeTimer(epochMillis: Double, down: Boolean, heading: Boolean) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var clockMillis by remember(epochMillis, down) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(epochMillis, down, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                val now = System.currentTimeMillis()
                clockMillis = now
                val wait = TimerPresentation.nextTickMillis(epochMillis, down, now) ?: break
                delay(wait)
            }
        }
    }
    Text(TimerPresentation.text(epochMillis, down, clockMillis),
        style = if (heading) PackTheme.heading else PackTheme.body)
}

/** Frame and pointer delivery for a Surface; the frame-target rule decides whether frames subscribe. */
@Composable private fun FramedGraphicsSurface(width: Double, height: Double, shapes: JsonValue?, pointerSlot: Int?, frameSlot: Int?,
                                             dispatch: (Int, String?) -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(frameSlot) {
        // A node without a declared onFrame is a static drawing: no frames are consumed or dispatched.
        if (FrameTarget.plan(frameSlot, null, 0L) == FramePlan.None) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var previous: Long? = null
            while (true) {
                val now = withFrameMillis { it }
                when (val plan = FrameTarget.plan(frameSlot, previous, now)) {
                    is FramePlan.Frame -> {
                        previous = now
                        dispatch(plan.slot, plan.deltaMillis.toString())
                    }
                    FramePlan.None -> return@repeatOnLifecycle
                }
            }
        }
    }
    GraphicsSurface(width, height, shapes, pointerSlot, dispatch)
}

@Composable private fun GraphicsSurface(width: Double, height: Double, shapes: JsonValue?, pointerSlot: Int?,
                                        dispatch: (Int, String?) -> Unit) {
    val encoded = androidx.compose.runtime.remember(shapes) { JSONArray(NeutralJson.render(shapes ?: JsonValue.Arr(emptyList()))) }
    val emit by rememberUpdatedState(dispatch)
    val slot by rememberUpdatedState(pointerSlot)
    val card = LocalCardTarget.current
    Canvas(Modifier.size(width.dp, height.dp).pointerInput(width, height) {
        awaitPointerEventScope {
            var down = emptyList<Long>()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        val phase = when { change.pressed && !change.previousPressed -> "down"; !change.pressed && change.previousPressed -> "up"; change.pressed -> "move"; else -> null }
                        // A card Surface delivers down then up only: never a drag.
                        if (phase != null && !(card && phase == "move")) slot?.let { target ->
                            emit(target, JSONObject().put("phase", phase).put("id", change.id.value)
                                .put("x", change.position.x * width / size.width.coerceAtLeast(1))
                                .put("y", change.position.y * height / size.height.coerceAtLeast(1)).toString())
                        }
                        change.consume()
                    }
                    down = event.changes.filter { it.pressed }.map { it.id.value }
                }
            } finally {
                // A gesture still down when the surface leaves the composition ends with one cancel
                // per pointer; an idle surface never fabricates one, so a covered page or a switched
                // tab panel dispatches no pointer event of its own.
                val target = slot
                if (target != null) for (id in down) emit(target, JSONObject().put("phase", "cancel").put("id", id).put("x", 0).put("y", 0).toString())
            }
        }
    }) {
        drawIntoCanvas { draw ->
            val canvas = draw.nativeCanvas
            val saved = canvas.save()
            try {
                canvas.scale(if (width == 0.0) 1f else size.width / width.toFloat(), if (height == 0.0) 1f else size.height / height.toFloat())
                paintShapes(canvas, encoded, 0, intArrayOf(0))
            } finally { canvas.restoreToCount(saved) }
        }
    }
}

/** Renders the checked tree. A fault draws nothing new: the last accepted tree stays in force. */
@Composable fun DealRenderer(node: JSONObject, target: String?, dispatch: (Int, String?) -> Unit) {
    val tree = treeFromJson(node)
    val verdict = IngressValidation.validate(tree, target)
    var previous by remember { mutableStateOf<UiNode?>(null) }
    val painting = if (verdict is IngressVerdict.Accepted) tree else previous
    SideEffect { if (verdict is IngressVerdict.Accepted) previous = tree }
    painting?.let { paint ->
        CompositionLocalProvider(LocalCardTarget provides (target == "card")) { PaintNode(paint, dispatch) }
    }
}

@Composable private fun ChildNodes(node: UiNode, dispatch: (Int, String?) -> Unit) {
    node.children.forEachIndexed { index, child ->
        key(child.key.ifEmpty { child.component }, index) { PaintNode(child, dispatch) }
    }
}

@Composable private fun PaintNode(node: UiNode, dispatch: (Int, String?) -> Unit) {
    val name = if (node.component == "root" || node.component == "__root") "Column" else node.component
    val component = RenderComponent.entry(name) ?: return
    val values = when (val resolution = PropResolution.resolve(name, node.props)) {
        is Resolution.Resolved -> Values(resolution.values)
        is Resolution.Fault -> return
    }
    val emit: (String, String?) -> Unit = { event, raw ->
        when (val payload = EventPayloads.reconstruct(name, event, values.all, raw)) {
            is EventPayload.Delivered -> values.slot(event)?.let { dispatch(it, payload.spelling) }
            else -> Unit
        }
    }
    val children: @Composable () -> Unit = { ChildNodes(node, dispatch) }
    val styleName = values.text("style")
    when (component) {
        RenderComponent.Column -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { children() }
        RenderComponent.Row -> AdaptiveRow(PackTheme.inputMinimumWidth.value.toInt().coerceAtLeast(160)) { children() }
        RenderComponent.Card -> {
            if (LocalFramed.current) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.groupGap)) { children() }
            else Surface(shape = RoundedCornerShape(PackTheme.surfaceRadius), color = PackTheme.surface) {
                CompositionLocalProvider(LocalFramed provides true) {
                    Column(Modifier.fillMaxWidth().padding(PackTheme.surfaceInset), verticalArrangement = Arrangement.spacedBy(PackTheme.groupGap)) { children() }
                }
            }
        }
        RenderComponent.Scroll -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PackTheme.groupGap)) { children() }
        RenderComponent.ActionBar -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PackTheme.tightGap), verticalAlignment = Alignment.CenterVertically) { children() }
        RenderComponent.TileGrid -> AdaptiveRow(PackTheme.metricMinimumWidth.value.toInt()) { children() }
        RenderComponent.Canvas -> Box(Modifier.fillMaxWidth()) { children() }
        RenderComponent.Layers -> Box(Modifier.size(values.number("width").dp, values.number("height").dp)) { children() }
        RenderComponent.AppTheme -> Column(Modifier.fillMaxWidth()) { children() }
        RenderComponent.Grid -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) { children() }
        RenderComponent.MetricGroup -> AdaptiveRow(PackTheme.metricMinimumWidth.value.toInt()) { children() }
        RenderComponent.KeyValueGroup -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) { children() }
        RenderComponent.AdaptiveColumns -> AdaptiveRow(values.number("minimumWidth", 240.0).toInt()) { children() }
        RenderComponent.Section -> {
            if (LocalFramed.current) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.groupGap)) {
                if (values.text("title").isNotEmpty()) Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                if (values.text("subtitle").isNotEmpty()) Text(values.text("subtitle"), style = PackTheme.caption, color = PackTheme.muted)
                children()
            } else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(PackTheme.surfaceRadius), color = PackTheme.surface) {
                CompositionLocalProvider(LocalFramed provides true) {
                    Column(Modifier.fillMaxWidth().padding(PackTheme.surfaceInset), verticalArrangement = Arrangement.spacedBy(PackTheme.groupGap)) {
                        if (values.text("title").isNotEmpty()) Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        if (values.text("subtitle").isNotEmpty()) Text(values.text("subtitle"), style = PackTheme.caption, color = PackTheme.muted)
                        children()
                    }
                }
            }
        }
        RenderComponent.SectionHeader -> Column(Modifier.fillMaxWidth()) {
            Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
                if (values.text("subtitle").isNotEmpty()) Text(values.text("subtitle"), style = PackTheme.caption, color = PackTheme.muted)
                if (values.text("count").isNotEmpty()) Text(values.text("count"), style = PackTheme.caption, color = PackTheme.accent)
            }
        }
        RenderComponent.Header -> Column(Modifier.fillMaxWidth()) {
            if (values.text("eyebrow").isNotEmpty()) Text(values.text("eyebrow"), style = PackTheme.caption, color = PackTheme.accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (values.text("icon").isNotEmpty()) PackIcon(values.text("icon"))
                Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            if (values.text("supporting").isNotEmpty()) Text(values.text("supporting"), style = PackTheme.caption, color = PackTheme.muted)
        }
        RenderComponent.Hero -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
            if (values.text("eyebrow").isNotEmpty()) Text(values.text("eyebrow"), style = PackTheme.caption, color = PackTheme.accent)
            Text(values.text("title"), style = PackTheme.hero, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            if (values.text("supporting").isNotEmpty()) Text(values.text("supporting"), style = PackTheme.body, color = PackTheme.muted)
            children()
        }
        RenderComponent.Text -> Text(values.text("text"), style = if (StyleFamilies.heading("Text", styleName)) PackTheme.heading else PackTheme.body)
        RenderComponent.NumberText -> Text(NumericPresentation.display(values.number("value"), values.all), style = PackTheme.value)
        RenderComponent.ClockText -> Text(NumericPresentation.format(values.number("minutes"), values.text("prefix"), values.text("suffix"), 2, 0))
        RenderComponent.Image -> ImageContent(values.text("src"), values.text("alt"))
        RenderComponent.Icon -> PackIcon(values.text("name"))
        RenderComponent.Badge -> Surface(shape = RoundedCornerShape(999.dp), color = PackTheme.accent) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (values.text("icon").isNotEmpty()) PackIcon(values.text("icon"), Modifier.size(12.dp))
                Text(values.text("text"), style = PackTheme.caption)
            }
        }
        RenderComponent.Stat -> LabeledValue(values.text("label"), values.text("value"), values.text("supporting"))
        RenderComponent.Tile -> Box(Modifier.fillMaxWidth(if (values.number("span", 1.0) == 2.0) 1f else .5f)) {
            LabeledValue(values.text("label"), values.text("value"), values.text("supporting"))
        }
        RenderComponent.NumberStat -> LabeledValue(values.text("label"), NumericPresentation.display(values.number("value"), values.all), values.text("supporting"))
        RenderComponent.KeyValueItem -> LabeledValue(values.text("label"), values.text("value"), values.text("supporting"))
        RenderComponent.ListGroup -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
            if (values.text("title").isNotEmpty()) Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold)
            children()
        }
        RenderComponent.Timeline -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
            if (values.text("title").isNotEmpty()) Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold)
            children()
        }
        RenderComponent.ListItem, RenderComponent.TimelineItem -> Surface(
            onClick = { if (values.flag("enabled", true)) emit("onTap", null) },
            enabled = values.flag("enabled", true), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel") }) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (values.text("icon").isNotEmpty()) PackIcon(values.text("icon"))
                Column(Modifier.weight(1f)) {
                    Text(values.text("title"), style = PackTheme.body)
                    if (values.text("subtitle").isNotEmpty()) Text(values.text("subtitle"), style = PackTheme.caption, color = PackTheme.muted)
                }
                if (values.text("trailing").isNotEmpty()) Text(values.text("trailing"), style = PackTheme.caption)
            }
        }
        RenderComponent.NumberListItem -> Surface(
            onClick = { if (values.flag("enabled", true)) emit("onTap", null) },
            enabled = values.flag("enabled", true), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel") }) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(values.text("title"), style = PackTheme.body)
                    if (values.text("subtitle").isNotEmpty()) Text(values.text("subtitle"), style = PackTheme.caption, color = PackTheme.muted)
                }
                Text(NumericPresentation.display(values.number("value"), values.all), style = PackTheme.value)
            }
        }
        RenderComponent.Table -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) { values.strings("headers").forEach { Text(it, Modifier.weight(1f), style = PackTheme.caption, color = PackTheme.muted) } }
            children()
        }
        RenderComponent.TableRow -> Row(Modifier.fillMaxWidth()) { values.strings("cells").forEach { Text(it, Modifier.weight(1f), style = PackTheme.body) } }
        RenderComponent.Button, RenderComponent.Cell -> {
            val label = values.text("text")
            val click = { if (values.flag("enabled", true)) emit("onClick", null) }
            val description = values.text("accessibilityLabel").ifEmpty { label }
            if (StyleFamilies.resolve(name, styleName) == "secondary") {
                OutlinedButton(onClick = click, enabled = values.flag("enabled", true),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { Text(label) }
            } else {
                Button(onClick = click, enabled = values.flag("enabled", true),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                    contentPadding = PaddingValues(16.dp)) { Text(label) }
            }
        }
        RenderComponent.IconButton -> IconButton(onClick = { if (values.flag("enabled", true)) emit("onClick", null) },
            enabled = values.flag("enabled", true),
            modifier = Modifier.size(48.dp).semantics { contentDescription = values.text("accessibilityLabel").ifEmpty { values.text("label") } }) {
            PackIcon(values.text("icon"))
        }
        RenderComponent.TextField -> {
            val authoritative = values.text("value")
            val multiline = styleName == "multiline"
            var draft by draftOf(authoritative, authoritative)
            OutlinedTextField(value = DraftText.shown(draft, authoritative, authoritative),
                onValueChange = { next -> draft = Draft(next, authoritative); emit("onChange", next) },
                label = { Text(values.text("label")) }, singleLine = !multiline,
                minLines = if (multiline) 2 else 1,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel") },
                shape = RoundedCornerShape(16.dp))
        }
        RenderComponent.NumberField -> {
            val lower = values.number("minimum"); val upper = values.number("maximum")
            val step = values.number("step", 1.0)
            val value = values.number("value")
            // The draft belongs to the bound value, not to a rounded rendering of it.
            val identity = DraftText.identity(value)
            val authoritative = NumericPresentation.format(value, "", "", 1, 0)
            var draft by draftOf(identity, authoritative)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = DraftText.shown(draft, identity, authoritative), onValueChange = { raw ->
                    draft = Draft(raw, identity)
                    raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in lower..upper }?.let { emit("onChange", it.toString()) }
                }, label = { Text(values.text("label")) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(1f).semantics { contentDescription = values.text("accessibilityLabel").ifEmpty { values.text("label") } })
                Text("\u00b1" + NumericPresentation.format(step, "", "", 1, 0), style = PackTheme.caption)
            }
        }
        RenderComponent.TimeField, RenderComponent.DateField -> {
            val value = values.number("value")
            val text = if (component == RenderComponent.TimeField) NumericPresentation.format(kotlin.math.floor(value / 60), "", "", 2, 0) + ":" +
                NumericPresentation.format(value % 60, "", "", 2, 0) else NumericPresentation.format(value, "", "", 1, 0)
            val identity = DraftText.identity(value)
            var draft by draftOf(identity, text)
            OutlinedTextField(value = DraftText.shown(draft, identity, text),
                onValueChange = { raw -> draft = Draft(raw, identity); raw.toDoubleOrNull()?.let { emit("onChange", it.toString()) } },
                label = { Text(values.text("label")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel").ifEmpty { values.text("label") } })
        }
        RenderComponent.Toggle, RenderComponent.Checkbox -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(values.text("label"), modifier = Modifier.weight(1f), style = PackTheme.body)
            Checkbox(checked = values.flag("checked"), onCheckedChange = { next -> emit("onChange", next.toString()) },
                modifier = Modifier.semantics { contentDescription = values.text("accessibilityLabel") })
        }
        RenderComponent.Slider -> Column(Modifier.fillMaxWidth()) {
            Text(values.text("label"), style = PackTheme.caption)
            Slider(value = values.number("value").toFloat(), valueRange = values.number("minimum").toFloat()..values.number("maximum").toFloat(),
                onValueChange = { next -> emit("onChange", next.toString()) })
        }
        RenderComponent.Select -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
            val selected = values.number("selected", -1.0).toInt()
            values.strings("labels").forEachIndexed { index, label ->
                FilterChip(selected = index == selected, onClick = { emit("onChange", index.toString()) }, label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = values.text("accessibilityLabel") })
            }
        }
        RenderComponent.Tabs -> {
            val labels = values.strings("labels")
            // Only the selected child is composed and it keeps the renderer's dispatch, so its
            // controls stay live; ingress guarantees a panel for an accepted tree.
            val panel = TabPanels.select(labels, values.number("selected"), node.children)
            val selected = panel?.index ?: -1
            val retained = rememberSaveableStateHolder()
            Column(Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selected.coerceAtLeast(0),
                    modifier = Modifier.semantics { contentDescription = values.text("accessibilityLabel") }) {
                    labels.forEachIndexed { index, label -> Tab(selected = index == selected, onClick = { emit("onChange", index.toString()) }, text = { Text(label) }) }
                }
                if (panel != null) {
                    // The holder restores the saveable state of a panel that left the composition.
                    retained.SaveableStateProvider(panel.identity) { PaintNode(panel.node, dispatch) }
                }
            }
        }
        RenderComponent.InsetBanner, RenderComponent.EmptyState -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(values.text("title"), style = PackTheme.heading, fontWeight = FontWeight.SemiBold)
                Text(values.text("message"), style = PackTheme.body)
                if (values.text("actionText").isNotEmpty()) TextButton(onClick = { emit("onAction", null) }) { Text(values.text("actionText")) }
            }
        }
        RenderComponent.Progress -> {
            val maximum = values.number("maximum")
            val capped = values.number("value").coerceIn(0.0, maximum)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(values.text("label"), style = PackTheme.caption)
                ProgressBar(if (maximum > 0) capped / maximum else 0.0)
            }
        }
        RenderComponent.ProgressRing -> {
            val maximum = values.number("maximum")
            val capped = values.number("value").coerceIn(0.0, maximum)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressDial(if (maximum > 0) capped / maximum else 0.0)
                Column { Text(values.text("label"), style = PackTheme.caption); if (values.text("text").isNotEmpty()) Text(values.text("text"), style = PackTheme.body) }
            }
        }
        RenderComponent.Spinner -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(values.text("label"), style = PackTheme.caption)
        }
        RenderComponent.Snackbar -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(values.text("message"), Modifier.weight(1f), style = PackTheme.body)
                if (values.text("actionText").isNotEmpty()) TextButton(onClick = { emit("onAction", null) }) { Text(values.text("actionText")) }
            }
        }
        RenderComponent.Skeleton -> Box(Modifier.size(values.number("width").dp, values.number("height").dp)
            .background(PackTheme.muted.copy(alpha = .2f), RoundedCornerShape(if (values.flag("rounded")) 12.dp else 0.dp)))
        RenderComponent.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(PackTheme.muted.copy(alpha = .3f)))
        RenderComponent.Spacer -> Spacer(Modifier.height(when (StyleFamilies.resolve("Spacer", styleName)) { "small" -> 4.dp; "large" -> 24.dp; "none" -> 0.dp; else -> 12.dp }))
        RenderComponent.Timer -> NativeTimer(values.number("millis"), values.flag("down"), StyleFamilies.heading("Timer", styleName))
        RenderComponent.Ticker -> {
            val running = values.flag("running")
            val interval = values.number("intervalMs").toLong().coerceAtLeast(1)
            LaunchedEffect(running, interval) { while (running) { delay(interval); emit("onTick", System.currentTimeMillis().toString()) } }
        }
        RenderComponent.BackHandler -> BackHandler(enabled = true) { emit("onBack", null) }
        RenderComponent.Path -> {
            val commands = values.text("commands")
            Canvas(Modifier.fillMaxWidth().height(160.dp).clickable { emit("onTap", null) }) {
                drawIntoCanvas { draw ->
                    val parsed = runCatching { androidx.core.graphics.PathParser.createPathFromPathData(commands) }.getOrNull() ?: return@drawIntoCanvas
                    draw.nativeCanvas.drawPath(parsed, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 2.dp.toPx()
                        color = android.graphics.Color.parseColor("#6540a1")
                    })
                }
            }
        }
        RenderComponent.Motion -> {
            val trigger = values.number("trigger")
            val animated = remember { Animatable(1f) }
            LaunchedEffect(trigger) { animated.snapTo(0f); animated.animateTo(1f) }
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = animated.value }) { children() }
        }
        RenderComponent.Transform -> {
            val pose = values.numbers("pose")
            Column(Modifier.fillMaxWidth().graphicsLayer {
                if (pose.size == 8) {
                    rotationX = pose[0].toFloat(); rotationY = pose[1].toFloat(); rotationZ = pose[2].toFloat()
                    translationX = pose[3].toFloat(); translationY = pose[4].toFloat(); scaleX = pose[6].toFloat(); scaleY = pose[6].toFloat()
                    alpha = pose[7].toFloat()
                }
            }) { children() }
        }
        RenderComponent.Stage -> Box(Modifier.fillMaxWidth().heightIn(min = values.number("height").dp)) { children() }
        RenderComponent.Surface ->
            FramedGraphicsSurface(values.number("width"), values.number("height"), values.json("shapes"),
                values.slot("onPointer"), values.slot("onFrame"), dispatch)
        RenderComponent.Sparkline -> {
            val series = values.numbers("series")
            val second = values.numbers("series2")
            if (series.isEmpty() && second.isEmpty()) Text("No chart data", style = PackTheme.caption, color = PackTheme.muted)
            else Column {
                if (values.text("label").isNotEmpty()) Text(values.text("label"), style = PackTheme.caption)
                SparklinePicture(series, second, values.number("maximum"), values.number("minimum"))
            }
        }
    }
}
