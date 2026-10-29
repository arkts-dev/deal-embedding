package dev.deal.embedding

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.deal.embedding.discovery.*

@Composable fun AssistedTask(
    facts: List<DiscoveryFact>,
    storageName: String = "assistance",
    title: String = "",
    protocolFactory: (Context) -> ApplicationProtocol,
    hooks: AssistanceHooks,
    inspect: (String, String, Long) -> Unit,
    review: @Composable (Experience) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentFacts by rememberUpdatedState(facts)
    val identity = DiscoveryFacts(facts).identity
    val help = remember(identity, storageName) { AssistanceController(context.applicationContext, { currentFacts }, protocolFactory, hooks) }
    DisposableEffect(help, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> help.resume()
            Lifecycle.Event.ON_PAUSE -> help.pause()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) help.resume()
        onDispose { lifecycle.removeObserver(observer); help.close() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (title.isNotEmpty()) Text(title, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium) else Spacer(Modifier.weight(1f))
            TextButton(onClick = { inspect(help.surface?.experience?.id.orEmpty(), help.trace, help.since) }) { Text("Inspect") }
        }
        if (help.failed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(help.status, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            Button(onClick = help::retry, enabled = !help.busy) { Text("Try again") }
        }
        help.surface?.let { surface ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!help.failed && help.status.isNotEmpty()) Text(help.status)
                ExperienceSurface(surface)
                review(surface.experience)
            }
        } ?: run {
            if (help.busy) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(help.status, style = MaterialTheme.typography.bodySmall)
            } else if (!help.failed && help.status.isNotEmpty()) Text(help.status, Modifier.padding(horizontal = 16.dp))
            help.suggestion?.let { tip ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                    SuggestionCard(context, tip, { help.open(tip) }, help::dismiss, help::presentationFailure)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
