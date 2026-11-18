package dev.deal.shell

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every app shares one expressive scheme; only the accent identifies the app. */
data class Accent(val id: String, val name: String, val light: Color, val dark: Color) {
    companion object {
        val Organizer = Accent("organizer", "Organizer", Color(0xff5b3fa8), Color(0xffc3adff))
        val Rental = Accent("rental", "Equipment Rental", Color(0xff1c6b93), Color(0xff8ad0f0))
        val Pizza = Accent("pizza", "Pizza", Color(0xffa4442c), Color(0xffffb59c))
        val Todo = Accent("todo", "Todo", Color(0xff2c6b4f), Color(0xff8fe0bb))
        val Calendar = Accent("calendar", "Calendar", Color(0xff8a5a1f), Color(0xffffd28f))
        val Deal = Accent("deal", "DEAL experience", Color(0xff6540a1), Color(0xffcbbaff))
    }
}

val LocalAccent = staticCompositionLocalOf { Accent.Organizer }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun AppTheme(accent: Accent, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = if (dark) accent.dark else accent.light,
        primaryContainer = (if (dark) accent.dark else accent.light).copy(alpha = if (dark) .22f else .12f),
        onPrimaryContainer = if (dark) accent.dark else accent.light.copy(alpha = .9f),
        secondaryContainer = base.surfaceContainerHigh,
        background = if (dark) Color(0xff0f1115) else Color(0xfff7f5f2),
        surface = if (dark) Color(0xff171a1f) else Color.White,
        surfaceContainer = if (dark) Color(0xff1f242b) else Color(0xffefece7),
        surfaceContainerHigh = if (dark) Color(0xff262c34) else Color(0xffe8e4de),
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialExpressiveTheme(colorScheme = scheme, content = content)
    }
}

/** A slow, wide gradient behind hero content. Expressive, never decorative noise. */
@Composable fun AuroraBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val accent = LocalAccent.current
    val dark = isSystemInDarkTheme()
    val shift = rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val a = accent.light.copy(alpha = if (dark) .34f else .20f)
    val b = accent.dark.copy(alpha = if (dark) .26f else .14f)
    Box(modifier.background(Brush.linearGradient(colors = listOf(a, b), start = Offset(0f, 400f * (1f + shift.value)), end = Offset(1200f, 900f * shift.value))), content = content)
}

@Composable fun Hero(eyebrow: String, title: String, subtitle: String, illustration: (@Composable () -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
    AuroraBackdrop(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))) {
        Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actions != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            illustration?.let { Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) { it() } }
        }
    }
}

@Composable fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            trailing?.invoke()
        }
        content()
    }
}

@Composable fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** Status is carried by text and icon first, colour second. */
@Composable fun StatusPill(text: String, tone: Tone) {
    val accent = LocalAccent.current
    val color = when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Attention -> MaterialTheme.colorScheme.error
        Tone.Progress -> MaterialTheme.colorScheme.primary
        Tone.Settled -> accent.light
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .14f)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
    }
}
enum class Tone { Neutral, Attention, Progress, Settled }

@Composable fun Meter(fraction: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow), label = "meter")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable fun EmptyState(title: String, body: String, art: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        art?.invoke()
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun KeyValue(key: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Code-drawn artwork. No binary assets, crisp at every density. */
object Art {
    @Composable fun Microphone(size: androidx.compose.ui.unit.Dp = 120.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36); val accent = Color(0xff8a8f99)
        drawRoundRect(ink, Offset(size.toPx() * .44f, size.toPx() * .08f), Size(size.toPx() * .12f, size.toPx() * .34f), androidx.compose.ui.geometry.CornerRadius(size.toPx() * .06f))
        drawArc(accent, 0f, 180f, false, Offset(size.toPx() * .3f, size.toPx() * .22f), Size(size.toPx() * .4f, size.toPx() * .3f), style = Stroke(size.toPx() * .045f, cap = StrokeCap.Round))
        drawLine(ink, Offset(size.toPx() * .5f, size.toPx() * .42f), Offset(size.toPx() * .5f, size.toPx() * .74f), size.toPx() * .045f)
        drawLine(ink, Offset(size.toPx() * .34f, size.toPx() * .78f), Offset(size.toPx() * .66f, size.toPx() * .78f), size.toPx() * .06f, StrokeCap.Round)
    }
    @Composable fun BoomStand(size: androidx.compose.ui.unit.Dp = 120.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36)
        drawLine(ink, Offset(size.toPx() * .3f, size.toPx() * .92f), Offset(size.toPx() * .5f, size.toPx() * .2f), size.toPx() * .04f)
        drawLine(ink, Offset(size.toPx() * .7f, size.toPx() * .92f), Offset(size.toPx() * .5f, size.toPx() * .2f), size.toPx() * .04f)
        drawLine(ink, Offset(size.toPx() * .5f, size.toPx() * .28f), Offset(size.toPx() * .86f, size.toPx() * .36f), size.toPx() * .04f)
        drawCircle(ink, size.toPx() * .035f, Offset(size.toPx() * .86f, size.toPx() * .36f))
    }
    @Composable fun Cable(size: androidx.compose.ui.unit.Dp = 120.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36)
        val path = Path().apply { moveTo(size.toPx() * .16f, size.toPx() * .3f); cubicTo(size.toPx() * .5f, size.toPx() * .95f, size.toPx() * .5f, size.toPx() * .05f, size.toPx() * .84f, size.toPx() * .7f) }
        drawPath(path, ink, style = Stroke(size.toPx() * .04f, cap = StrokeCap.Round))
        drawCircle(ink, size.toPx() * .05f, Offset(size.toPx() * .16f, size.toPx() * .3f))
        drawCircle(ink, size.toPx() * .05f, Offset(size.toPx() * .84f, size.toPx() * .7f))
    }
    @Composable fun Amplifier(size: androidx.compose.ui.unit.Dp = 120.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36); val cloth = Color(0xff4a3b2c)
        drawRoundRect(ink, Offset(size.toPx() * .1f, size.toPx() * .24f), Size(size.toPx() * .8f, size.toPx() * .62f), androidx.compose.ui.geometry.CornerRadius(size.toPx() * .05f))
        drawRect(cloth, Offset(size.toPx() * .16f, size.toPx() * .36f), Size(size.toPx() * .68f, size.toPx() * .44f))
        drawCircle(Color(0xffd8c48a), size.toPx() * .05f, Offset(size.toPx() * .24f, size.toPx() * .3f))
    }
    @Composable fun Pizza(size: androidx.compose.ui.unit.Dp = 120.dp) = Canvas(Modifier.size(size)) {
        val crust = Color(0xffd9a05b); val cheese = Color(0xfff2c14e); val sauce = Color(0xffc0392b)
        drawCircle(crust, size.toPx() * .44f, Offset(size.toPx() * .5f, size.toPx() * .5f))
        drawCircle(cheese, size.toPx() * .36f, Offset(size.toPx() * .5f, size.toPx() * .5f))
        listOf(Offset(.38f, .38f), Offset(.62f, .44f), Offset(.46f, .62f), Offset(.64f, .66f), Offset(.34f, .54f)).forEach { drawCircle(sauce, size.toPx() * .055f, Offset(size.toPx() * it.x, size.toPx() * it.y)) }
    }
    @Composable fun Stage(size: androidx.compose.ui.unit.Dp = 150.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36)
        drawRoundRect(ink.copy(alpha = .12f), Offset(0f, size.toPx() * .72f), Size(size.toPx(), size.toPx() * .1f))
        drawRoundRect(ink.copy(alpha = .22f), Offset(size.toPx() * .1f, size.toPx() * .5f), Size(size.toPx() * .8f, size.toPx() * .22f))
        for (i in 0..4) drawCircle(ink, size.toPx() * .022f, Offset(size.toPx() * (.2f + i * .15f), size.toPx() * .3f))
        drawLine(ink.copy(alpha = .5f), Offset(size.toPx() * .5f, size.toPx() * .2f), Offset(size.toPx() * .5f, size.toPx() * .5f), size.toPx() * .015f)
    }
    @Composable fun Speaker(size: androidx.compose.ui.unit.Dp = 42.dp) = Canvas(Modifier.size(size)) {
        val ink = Color(0xff2b2f36)
        drawRoundRect(ink, Offset(0f, size.toPx() * .1f), Size(size.toPx() * .6f, size.toPx() * .8f), androidx.compose.ui.geometry.CornerRadius(size.toPx() * .06f))
        drawCircle(ink, size.toPx() * .2f, Offset(size.toPx() * .8f, size.toPx() * .3f))
        drawCircle(ink, size.toPx() * .12f, Offset(size.toPx() * .8f, size.toPx() * .7f))
    }
}
