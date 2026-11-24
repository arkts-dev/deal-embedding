package dev.deal.embedding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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
private val LocalTextPattern = staticCompositionLocalOf<TextPattern> { error("No renderer pattern mechanism") }
private val LocalStageDepth = compositionLocalOf { false }

/** The Stage's shared perspective in dp; a Transform reads it for its depth cue. */
private val LocalPerspective = compositionLocalOf { PoseMotion.DEFAULT_PERSPECTIVE_DP }

/** One Motion trajectory lasts this long; only presentation timing lives here. */
private const val MOTION_MILLIS = 420

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
@Composable private fun AdaptiveRow(minimumChildWidth: Int, gap: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val minimum = with(density) { minimumChildWidth.coerceAtLeast(48).dp.toPx() * density.fontScale.coerceAtLeast(1f) }.toInt()
    val gapPx = with(density) { gap.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { children, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else minimum
        layoutGrid(children, constraints, ((width.toLong() + gapPx) / (minimum.toLong() + gapPx)).toInt(), gapPx, minimum)
    }
}

/**
 * Lay children out in the declared number of equal columns, one row per group. The count is the
 * declared `columns` prop of Grid, MetricGroup and KeyValueGroup, and the adaptive AdaptiveColumns
 * decision; a declared count is honoured instead of being flattened into one column or a wrap.
 */
@Composable private fun ColumnGrid(columns: Int, gap: Dp, content: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { children, constraints ->
        layoutGrid(children, constraints, columns, gapPx, 0)
    }
}

/**
 * The column-placement mechanism the declared grid and the adaptive row both measure through. A
 * bounded width divides into as many of the decided columns as the gaps leave room for; an
 * unbounded one stacks one child per row at its own width, reported as the widest child, never as a
 * zero-width row.
 */
private fun androidx.compose.ui.layout.MeasureScope.layoutGrid(
    children: List<androidx.compose.ui.layout.Measurable>, constraints: Constraints, columns: Int, gap: Int,
    fallbackWidth: Int,
): androidx.compose.ui.layout.MeasureResult {
    val bounded = constraints.hasBoundedWidth
    val width = if (bounded) constraints.maxWidth else fallbackWidth
    // A width that cannot hold the declared columns and their gaps keeps only the columns that fit.
    val fitting = if (gap > 0) (width + gap) / (gap + 1) else columns
    val count = columns.coerceIn(1, children.size.coerceAtLeast(1)).coerceAtMost(fitting.coerceAtLeast(1))
    val cell = if (bounded && width > 0) ((width - gap * (count - 1)) / count).coerceAtLeast(0) else 0
    val measured = children.map {
        if (bounded && width > 0) it.measure(Constraints(minWidth = cell, maxWidth = cell))
        else it.measure(Constraints(maxWidth = width.coerceAtLeast(0)))
    }
    val placed = if (bounded && width > 0) measured.chunked(count) else measured.map { listOf(it) }
    val contentWidth = if (bounded) width else (measured.maxOfOrNull { it.width } ?: 0)
    val height = placed.sumOf { row -> row.maxOf { it.height } } + gap * (placed.size - 1).coerceAtLeast(0)
    return layout(contentWidth, constraints.constrainHeight(height)) {
        var y = 0
        for (row in placed) {
            row.forEachIndexed { index, child -> child.placeRelative(index * (cell + gap), y) }
            y += row.maxOf { it.height } + gap
        }
    }
}

/** The theme geometry one declared gap token names. */
private fun GapToken.distance(): Dp = when (this) {
    GapToken.TIGHT -> PackTheme.tightGap
    GapToken.GROUP -> PackTheme.groupGap
    GapToken.SCREEN -> PackTheme.screenGap
}

private fun Horizontal.alignment(): Alignment.Horizontal = when (this) {
    Horizontal.CENTER -> Alignment.CenterHorizontally
    Horizontal.END -> Alignment.End
    Horizontal.START -> Alignment.Start
}

/** A declared Column mode: its gap, its horizontal placement and its declared surface tone. */
@Composable private fun ColumnBody(mode: ColumnMode, content: @Composable () -> Unit) {
    val gap = mode.gap.distance()
    val align = mode.align.alignment()
    when (mode.tone) {
        Tone.NONE -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = align) { content() }
        Tone.SURFACE, Tone.ACCENT -> Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(PackTheme.surfaceRadius),
            color = if (mode.tone == Tone.SURFACE) PackTheme.surface else PackTheme.accent.copy(alpha = .12f)) {
            Column(Modifier.fillMaxWidth().padding(PackTheme.surfaceInset), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = align) { content() }
        }
    }
}

/** A declared Row mode: the adaptive wrap for the plain values, one anchored row for center and end,
 *  and the row-only split (spread to the edges) and joined (no gap) arrangements. */
@Composable private fun RowBody(mode: RowMode, minimumChildWidth: Int, content: @Composable () -> Unit) {
    val gap = mode.gap.distance()
    val body: @Composable () -> Unit = {
        if (mode.arrangement == RowArrangement.WRAP) {
            AdaptiveRow(minimumChildWidth, gap) { content() }
        } else {
            val arrangement = when (mode.arrangement) {
                RowArrangement.CENTER -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                RowArrangement.END -> Arrangement.spacedBy(gap, Alignment.End)
                RowArrangement.SPLIT -> Arrangement.SpaceBetween
                RowArrangement.JOINED -> Arrangement.spacedBy(0.dp)
                RowArrangement.WRAP -> Arrangement.spacedBy(gap)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) { content() }
        }
    }
    when (mode.tone) {
        Tone.NONE -> body()
        Tone.SURFACE, Tone.ACCENT -> Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(PackTheme.surfaceRadius),
            color = if (mode.tone == Tone.SURFACE) PackTheme.surface else PackTheme.accent.copy(alpha = .12f)) {
            Box(Modifier.fillMaxWidth().padding(PackTheme.surfaceInset)) { body() }
        }
    }
}

/** The painted tone of the second chart series, from its declared common-family value. */
@Composable private fun ChartTone(name: String): Color = when (name) {
    "accent" -> PackTheme.accent
    "success" -> MaterialTheme.colorScheme.secondary
    "warning" -> MaterialTheme.colorScheme.tertiary
    "danger" -> MaterialTheme.colorScheme.error
    else -> PackTheme.muted
}

/** The painted stroke of a declared Path style: the common family's own tone. The empty and default
 *  values paint the theme accent, which is the renderer's own primary, so a host theme decides it. */
@Composable private fun PathTone(name: String): Color = when (StyleFamilies.resolve("Path", name)) {
    "success" -> MaterialTheme.colorScheme.secondary
    "warning" -> MaterialTheme.colorScheme.tertiary
    "danger" -> MaterialTheme.colorScheme.error
    "muted" -> PackTheme.muted
    else -> PackTheme.accent
}

/** The platform keys a declared keyboardType asks for; the hint never restricts the typed value. */
private fun keyboardType(name: String): KeyboardType = when (TextFieldRules.keyboard(name)) {
    KeyboardHint.NUMBER -> KeyboardType.Number
    KeyboardHint.DECIMAL -> KeyboardType.Decimal
    KeyboardHint.EMAIL -> KeyboardType.Email
    KeyboardHint.PHONE -> KeyboardType.Phone
    KeyboardHint.URI -> KeyboardType.Uri
    KeyboardHint.PASSWORD -> KeyboardType.Password
    KeyboardHint.TEXT -> KeyboardType.Text
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

@Composable private fun SparklinePicture(series: List<Double>, second: List<Double>, count: Int, maximum: Double, minimum: Double, mode: ChartMode) {
    val accent = PackTheme.accent
    val secondTone = ChartTone(mode.tone)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (!ChartModes.plottable(minimum, maximum)) return@Canvas
        if (mode.bars) {
            fun bars(values: List<Double>, color: Color) {
                for (bar in ChartModes.bars(values, count, minimum, maximum, size.width, size.height)) {
                    drawRect(color, topLeft = Offset(bar.left, bar.top), size = Size(bar.right - bar.left, bar.bottom - bar.top))
                }
            }
            bars(series, accent)
            bars(second, secondTone)
        } else {
            fun plot(values: List<Double>, color: Color) {
                val points = ChartModes.points(values, count, minimum, maximum, size.width, size.height)
                if (points.isEmpty()) return
                val path = Path()
                points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            plot(series, accent)
            plot(second, secondTone)
        }
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
                                             label: String, dispatch: (Int, String?) -> Unit) {
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
    GraphicsSurface(width, height, shapes, pointerSlot, label, dispatch)
}

@Composable private fun GraphicsSurface(width: Double, height: Double, shapes: JsonValue?, pointerSlot: Int?,
                                        label: String, dispatch: (Int, String?) -> Unit) {
    val encoded = androidx.compose.runtime.remember(shapes) { JSONArray(NeutralJson.render(shapes ?: JsonValue.Arr(emptyList()))) }
    val emit by rememberUpdatedState(dispatch)
    val slot by rememberUpdatedState(pointerSlot)
    val card = LocalCardTarget.current
    Canvas(Modifier.size(width.dp, height.dp).semantics { contentDescription = label }.pointerInput(width, height) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val limits = LocalExecutionLimits.current
    val patterns = remember(context, limits) { TextPattern(context, limits) }
    LaunchedEffect(patterns) {
        try { awaitCancellation() }
        finally { withContext(NonCancellable + Dispatchers.IO) { patterns.close() } }
    }
    fun hasPattern(node: UiNode): Boolean =
        (node.component == "TextField" && (node.prop("pattern") as? UiValue.Text)?.value.orEmpty().isNotEmpty()) ||
            node.children.any(::hasPattern)
    val needsCheck = hasPattern(tree)
    var checked by remember(patterns) { mutableStateOf<UiNode?>(null) }
    LaunchedEffect(tree, patterns) {
        if (verdict is IngressVerdict.Accepted && needsCheck) {
            try {
                withContext(Dispatchers.IO) {
                    fun visit(node: UiNode) {
                        if (node.component == "TextField") {
                            val pattern = (node.prop("pattern") as? UiValue.Text)?.value.orEmpty()
                            if (pattern.isNotEmpty()) patterns.matches(pattern, "")
                        }
                        node.children.forEach(::visit)
                    }
                    visit(tree)
                }
                checked = tree
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                android.util.Log.w("DealRenderer", "Pattern admission failed", error)
            }
        }
    }
    val admitted = verdict is IngressVerdict.Accepted && (!needsCheck || checked == tree)
    val painting = if (admitted) tree else previous
    SideEffect { if (admitted) previous = tree }
    painting?.let { paint ->
        CompositionLocalProvider(LocalCardTarget provides (target == "card"), LocalTextPattern provides patterns) { PaintNode(paint, dispatch) }
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
        RenderComponent.Column -> ColumnBody(ContainerModes.column(styleName)) { children() }
        RenderComponent.Row -> RowBody(ContainerModes.row(styleName), PackTheme.inputMinimumWidth.value.toInt().coerceAtLeast(160)) { children() }
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
        RenderComponent.TileGrid -> AdaptiveRow(PackTheme.metricMinimumWidth.value.toInt(), PackTheme.groupGap) { children() }
        RenderComponent.Canvas -> Box(Modifier.fillMaxWidth()) { children() }
        RenderComponent.Layers -> Box(Modifier.size(values.number("width").dp, values.number("height").dp)) { children() }
        RenderComponent.AppTheme -> Column(Modifier.fillMaxWidth()) { children() }
        RenderComponent.Grid -> ColumnGrid(ContainerModes.columns(values.number("columns", 1.0)), ContainerModes.gridGap("Grid", styleName).distance()) { children() }
        RenderComponent.MetricGroup -> ColumnGrid(ContainerModes.columns(values.number("columns", 1.0)), ContainerModes.gridGap("MetricGroup", styleName).distance()) { children() }
        RenderComponent.KeyValueGroup -> ColumnGrid(ContainerModes.columns(values.number("columns", 1.0)), ContainerModes.gridGap("KeyValueGroup", styleName).distance()) { children() }
        RenderComponent.AdaptiveColumns -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            // The minimum is a font-scaled text width, so a scaled font never overflows its column.
            val minimumPx = with(density) {
                (values.number("minimumWidth", 240.0).dp.toPx() * density.fontScale.coerceAtLeast(1f)).toInt()
            }
            val gap = ContainerModes.gridGap("AdaptiveColumns", styleName).distance()
            val gapPx = with(density) { gap.roundToPx() }
            ColumnGrid(ContainerModes.adaptiveColumns(widthPx, minimumPx, gapPx), gap) { children() }
        }
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
            val multiline = StyleFamilies.resolve("TextField", styleName) == "multiline"
            var draft by draftOf(authoritative, authoritative)
            val shown = DraftText.shown(draft, authoritative, authoritative)
            val pattern = values.text("pattern")
            val checker = LocalTextPattern.current
            var patternMatches by remember(shown, pattern, checker) { mutableStateOf<Boolean?>(null) }
            var patternUnavailable by remember(shown, pattern, checker) { mutableStateOf(false) }
            LaunchedEffect(shown, pattern, checker) {
                if (pattern.isNotEmpty() && shown.isNotEmpty()) {
                    try { patternMatches = withContext(Dispatchers.IO) { checker.matches(pattern, shown) } }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { patternUnavailable = true }
                }
            }
            // Declared feedback explains a violated rule; it never replaces DEAL validation and never
            // suppresses input, so the typed text stays the field's value and is always dispatched.
            val feedback = TextFieldRules.feedback(
                shown,
                required = values.flag("required"),
                email = values.flag("email"),
                minLength = values.number("minLength"),
                maxLength = values.number("maxLength"),
                pattern = values.text("pattern"),
                requiredMessage = values.text("requiredMessage"),
                emailMessage = values.text("emailMessage"),
                minLengthMessage = values.text("minLengthMessage"),
                maxLengthMessage = values.text("maxLengthMessage"),
                patternMessage = values.text("patternMessage"),
                patternMatches = patternMatches,
            )
            val message = feedback?.message ?: if (patternUnavailable) "Pattern validation unavailable" else ""
            val supporting: (@Composable () -> Unit)? = if (message.isEmpty()) null else ({ Text(message) })
            OutlinedTextField(value = shown,
                onValueChange = { next -> draft = Draft(next, authoritative); emit("onChange", next) },
                label = { Text(values.text("label")) }, singleLine = !multiline,
                minLines = if (multiline) 2 else 1, isError = feedback != null || patternUnavailable,
                supportingText = supporting,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType(values.text("keyboardType"))),
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
                    // The checked decimal spelling, inside the declared bounds; every other spelling
                    // stays visible and uncommitted.
                    DraftConversion.decimal(raw)?.takeIf { it in lower..upper }?.let { emit("onChange", it.toString()) }
                }, label = { Text(values.text("label")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).semantics { contentDescription = values.text("accessibilityLabel").ifEmpty { values.text("label") } })
                Text("\u00b1" + NumericPresentation.format(step, "", "", 1, 0), style = PackTheme.caption)
            }
        }
        RenderComponent.TimeField, RenderComponent.DateField -> {
            val time = component == RenderComponent.TimeField
            val value = values.number("value")
            val text = if (time) DraftConversion.timeText(value) else DraftConversion.dayText(value)
            val identity = DraftText.identity(value)
            var draft by draftOf(identity, text)
            OutlinedTextField(value = DraftText.shown(draft, identity, text),
                onValueChange = { raw ->
                    draft = Draft(raw, identity)
                    // The shared temporal domain: minute-of-day 0-1439 or an integral local epoch-day.
                    (if (time) DraftConversion.timeOfDay(raw) else DraftConversion.day(raw))?.let { emit("onChange", it.toString()) }
                },
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
                onValueChange = { next -> emit("onChange", next.toString()) },
                modifier = Modifier.semantics { contentDescription = values.text("accessibilityLabel") })
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
        RenderComponent.InsetBanner, RenderComponent.EmptyState -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel") }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (values.text("icon").isNotEmpty()) PackIcon(values.text("icon"))
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
        RenderComponent.Snackbar -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().semantics { contentDescription = values.text("accessibilityLabel") }) {
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
            // The declared path is parsed once per command string, never once per frame.
            val parsed = remember(commands) {
                runCatching { androidx.core.graphics.PathParser.createPathFromPathData(commands) }.getOrNull()
            }
            val stroke = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE } }
            val spinPeriod = values.number("spinPeriodMs")
            val tone = PathTone(styleName)
            // A card Path cannot rotate, so it paints still; ingress refuses the declared rotation
            // before activation, and the painter never animates one on the card target.
            val spins = !LocalCardTarget.current && spinPeriod > 0
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            var rotation by remember(spinPeriod, spins) { mutableStateOf(0f) }
            LaunchedEffect(spinPeriod, spins, lifecycle) {
                if (!spins) return@LaunchedEffect
                // Only visible frames advance the declared rotation: the phase the covered page
                // skipped is never replayed, and a resume continues from the visible time so far.
                var visible = 0L
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    var resumedAt: Long? = null
                    var last = 0L
                    try {
                        while (true) {
                            withFrameMillis { now ->
                                val start = resumedAt ?: now.also { resumedAt = it }
                                last = now
                                rotation = PathSpin.rotationDegrees(spinPeriod, visible + (now - start))
                            }
                        }
                    } finally {
                        resumedAt?.let { start -> visible += (last - start).coerceAtLeast(0L) }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(160.dp).clickable { emit("onTap", null) }.semantics { contentDescription = values.text("accessibilityLabel") }) {
                val path = parsed ?: return@Canvas
                rotate(rotation) {
                    drawIntoCanvas { draw ->
                        stroke.strokeWidth = 2.dp.toPx()
                        stroke.color = tone.toArgb()
                        draw.nativeCanvas.drawPath(path, stroke)
                    }
                }
            }
        }
        RenderComponent.Motion -> {
            val trigger = values.number("trigger")
            val frames = MotionPresets.preset(StyleFamilies.resolve("Motion", styleName))
            // The trigger decides when a preset plays, never whether the first render animates.
            var seen by remember { mutableStateOf<Double?>(null) }
            val progress = remember { Animatable(1f) }
            LaunchedEffect(trigger) {
                val animate = MotionPresets.animates(seen, trigger)
                seen = trigger
                if (animate) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(MOTION_MILLIS))
                }
            }
            Column(Modifier.fillMaxWidth().graphicsLayer {
                val frame = MotionPresets.sample(frames, progress.value)
                scaleX = frame.scale
                scaleY = frame.scale
                rotationZ = frame.rotate
                translationX = frame.moveX * density
                translationY = frame.moveY * density
                alpha = frame.alpha
            }) { children() }
        }
        RenderComponent.Transform -> {
            val pose = PoseMotion.normalised(values.numbers("pose"))
            val duration = values.number("durationMs")
            val size = values.number("size")
            val solid = StyleFamilies.resolve("Transform", styleName) == "solid"
            val perspective = LocalPerspective.current
            val density = LocalDensity.current.density
            // The committed pose is interpolated from the previous one over the declared duration;
            // a first render paints the declared pose without animating it.
            val applied = remember { mutableStateOf(pose) }
            val progress = remember { Animatable(1f) }
            LaunchedEffect(pose, duration) {
                val start = applied.value
                if (start == pose) return@LaunchedEffect
                // The declared duration is the trajectory length: a zero duration applies the
                // committed pose at once, so the declared pose is never dropped.
                if (!PoseMotion.animates(duration)) { applied.value = pose; return@LaunchedEffect }
                progress.snapTo(0f)
                progress.animateTo(1f, tween(duration.toInt())) {
                    applied.value = PoseMotion.interpolate(start, pose, value.toDouble())
                }
            }
            val sizing = if (size > 0) Modifier.size(size.dp) else Modifier.fillMaxWidth()
            // Keep keyed children in source order; Stage layers sort displayed, not target, depth.
            val stage = LocalStageDepth.current
            Column(sizing.zIndex(if (stage) PoseMotion.depth(applied.value).toFloat() else 0f).graphicsLayer {
                val frame = applied.value
                rotationX = frame[0].toFloat()
                rotationY = frame[1].toFloat()
                rotationZ = frame[2].toFloat()
                translationX = frame[3].toFloat() * density
                translationY = frame[4].toFloat() * density
                scaleX = frame[6].toFloat()
                scaleY = frame[6].toFloat()
                cameraDistance = PoseMotion.cameraDistance(perspective, density)
                // A solid Transform hides the backface it is turned to, never paints it mirrored.
                alpha = if (solid && PoseMotion.backface(frame)) 0f else frame[7].toFloat()
                // moveZ reaches the sibling paint order through the same interpolated pose.
            }) { children() }
        }
        RenderComponent.Stage -> {
            val perspective = values.number("perspective", 900.0)
            Box(Modifier.fillMaxWidth().heightIn(min = values.number("height").dp)) {
                CompositionLocalProvider(LocalPerspective provides perspective, LocalStageDepth provides true) {
                    Box(Modifier.fillMaxWidth()) {
                        // Transform layers sort their interpolated depth; ties keep source order.
                        children()
                    }
                }
            }
        }
        RenderComponent.Surface ->
            FramedGraphicsSurface(values.number("width"), values.number("height"), values.json("shapes"),
                values.slot("onPointer"), values.slot("onFrame"), values.text("accessibilityLabel"), dispatch)
        RenderComponent.Sparkline -> {
            val series = values.numbers("series")
            val second = values.numbers("series2")
            val mode = ChartModes.mode(series, second, values.flag("bars"), values.text("series2Style"))
            if (mode.explanation != null) Text(mode.explanation, style = PackTheme.caption, color = PackTheme.muted)
            else {
                val count = ChartModes.count(series, second)
                // A declared category keeps its own position: a non-string item leaves its point
                // unlabelled instead of moving every later label onto the wrong point.
                val categories = ((values.json("categories") as? JsonValue.Arr)?.items ?: emptyList())
                    .map { (it as? JsonValue.Str)?.value }
                Column(verticalArrangement = Arrangement.spacedBy(PackTheme.tightGap)) {
                    if (values.text("label").isNotEmpty()) Text(values.text("label"), style = PackTheme.caption)
                    SparklinePicture(series, second, count, values.number("maximum"), values.number("minimum"), mode)
                    if (categories.isNotEmpty()) {
                        // One cell per point keeps every label under the point it names, even where the
                        // tree declares no label for a point.
                        Row(Modifier.fillMaxWidth()) {
                            for (index in 0 until count) {
                                val label = ChartModes.category(index, categories)
                                Box(Modifier.weight(1f)) { if (label.isNotEmpty()) Text(label, style = PackTheme.caption, color = PackTheme.muted) }
                            }
                        }
                    }
                    if (values.text("seriesLabel").isNotEmpty() || values.text("series2Label").isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PackTheme.tightGap), verticalAlignment = Alignment.CenterVertically) {
                            if (values.text("seriesLabel").isNotEmpty()) Text(values.text("seriesLabel"), style = PackTheme.caption, color = PackTheme.accent)
                            if (values.text("series2Label").isNotEmpty()) Text(values.text("series2Label"), style = PackTheme.caption, color = ChartTone(mode.tone))
                        }
                    }
                }
            }
        }
    }
}
