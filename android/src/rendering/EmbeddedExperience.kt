package dev.deal.embedding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A visible, accessible boundary around generated content; never a native consent surface. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun EmbeddedExperience(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xffcbbaff), onPrimary = Color(0xff291548),
        surface = Color(0xff272038), onSurface = Color(0xfff1eaff),
        surfaceContainer = Color(0xff21192f), outline = Color(0xff8876aa),
    ) else lightColorScheme(
        primary = Color(0xff6540a1), onPrimary = Color.White,
        surface = Color(0xfffaf7ff), onSurface = Color(0xff251b35),
        surfaceContainer = Color(0xffeee5fc), outline = Color(0xffaa91cd),
    )
    MaterialExpressiveTheme(colorScheme = scheme) {
        Surface(shape = RoundedCornerShape(32.dp), color = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("DEAL EXPERIENCE", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                content()
            }
        }
    }
}
