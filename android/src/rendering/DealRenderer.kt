package dev.deal.embedding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject

internal enum class RenderComponent(val component: String, val capability: String) {
    Column("Column", "renderer.compose.column"), Card("Card", "renderer.compose.card"),
    Text("Text", "renderer.compose.text"), Hero("Hero", "renderer.compose.hero"),
    Button("Button", "renderer.compose.button"), Toggle("Toggle", "renderer.compose.toggle"),
    IntText("IntText", "renderer.compose.integer"), Time("Time", "renderer.compose.time"),
    Spinner("Spinner", "renderer.compose.spinner");
    companion object {
        fun verify(pack: deal.ui.UiModel.PackModule) {
            val declared = pack.components().mapValues { (_, c) -> c.contracts().filterIsInstance<deal.ui.UiModel.Capability>().map { it.name() }.single() }
            check(declared == entries.associate { it.component to it.capability }) { "Renderer and component pack disagree" }
        }
    }
}

/** Renders checked portable nodes only. State transitions and actions remain in DEAL. */
@Composable fun DealRenderer(node: JSONObject, dispatch: (Int, String?) -> Unit) {
    val properties = node.getJSONArray("props")
    fun prop(name: String): JSONObject? = (0 until properties.length()).map { properties.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
    fun text(name: String) = prop(name)?.optString("stringValue") ?: ""
    @Composable fun children() {
        val nodes = node.getJSONArray("children")
        for (i in 0 until nodes.length()) {
            val child = nodes.getJSONObject(i)
            key(child.getString("structural"), child.getString("keyKind"), child.getInt("keyInt"), child.getString("keyString")) { DealRenderer(child, dispatch) }
        }
    }
    val name = node.getString("component").substringAfterLast('.')
    val component = if (name == "root" || name == "__root") RenderComponent.Column else RenderComponent.entries.single { it.component == name }
    when (component) {
        RenderComponent.Column -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { children() }
        RenderComponent.Card -> Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { children() }
        }
        RenderComponent.Text -> Text(text("value"), style = MaterialTheme.typography.bodyLarge)
        RenderComponent.Hero -> Text(text("value"), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        RenderComponent.Button -> Button(onClick = { prop("onClick")?.let { dispatch(it.getInt("actionSlot"), null) } }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = text("accessibilityLabel") }, contentPadding = PaddingValues(18.dp)) { Text(text("text")) }
        RenderComponent.Toggle -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text("text"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Checkbox(prop("checked")?.getBoolean("booleanValue") ?: false, { prop("onChange")?.let { dispatch(it.getInt("actionSlot"), text("value")) } }, modifier = Modifier.semantics { contentDescription = text("accessibilityLabel") })
        }
        RenderComponent.IntText -> Text((prop("value")?.getInt("intValue") ?: 0).toString(), style = MaterialTheme.typography.headlineMedium)
        RenderComponent.Time -> { val minute = prop("value")?.getInt("intValue") ?: 0; Text("%02d:%02d".format(minute / 60, minute % 60), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary) }
        RenderComponent.Spinner -> CircularProgressIndicator()
    }
}
