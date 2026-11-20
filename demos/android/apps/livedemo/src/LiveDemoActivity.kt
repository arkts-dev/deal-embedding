package dev.deal.apps.livedemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deal.embedding.*
import dev.deal.embedding.discovery.DiscoveryFact
import dev.deal.shell.*

/** New app, ordinary public AAR consumer. No supplied source or inference substitute. */
@OptIn(ExperimentalMaterial3Api::class)
class LiveDemoActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Optional isolated measurement run. Existing tools and their links stay intact.
        val run = intent.getStringExtra("run").orEmpty()
        require(run.isEmpty() || run.matches(Regex("[a-z0-9-]{1,40}")))
        val store = if (run.isEmpty()) "assistance" else "picnic-$run"
        val runHooks = if (run.isEmpty()) null else object : DemoAssistanceHooks(this) {
            override fun saved(identity: String) = super.saved("$store.$identity")
            override fun save(identity: String, experience: String) = super.save("$store.$identity", experience)
            override fun dismissed(identity: String) = super.dismissed("$store.$identity")
            override fun dismiss(identity: String) = super.dismiss("$store.$identity")
        }
        setContent { AppTheme(Accent.Deal) {
            var enabled by remember { mutableStateOf(consented()) }
            var menu by remember { mutableStateOf(false) }
            Scaffold(topBar = { TopAppBar(title = { Text("Picnic") }, actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false; startActivity(AssistanceShell.settings(this@LiveDemoActivity))
                        })
                    }
                }
            }) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    if (!enabled) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("A little help with the budget", style = MaterialTheme.typography.headlineSmall)
                        Text("Discover a useful tool from the picnic note, then open it to generate an interactive screen.")
                        Text("The note goes to DeepSeek Flash for discovery, ${GenerationBuild.label} for planning/source generation and Jev for selection/coverage. Real AI requests may cost money. No other apps or private records are accessed.")
                        Button(onClick = {
                            check(Connections.settings(this@LiveDemoActivity).edit()
                                .putBoolean("discovery.deepseek-flash", true).putBoolean("generation", true).commit())
                            enabled = true
                        }) { Text("Enable live AI") }
                    } else AssistedTask(
                        facts = picnicFacts, storageName = store, title = "Your picnic", hooks = runHooks,
                        protocolFactory = { context ->
                            Embedding.open(context, EmbeddingConfig(store, GenerationBuild.gateway(),
                                checkNotNull(AssistanceBuild.discoveryGateway()) { "Build with cloud discovery enabled" }, retainState = true))
                                .also { it.capture(true) } // Inspect retains real model input/output for this disclosed demo note.
                        },
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Saturday picnic", style = MaterialTheme.typography.headlineSmall)
                            Text("Budget €120 · spent €84", style = MaterialTheme.typography.titleMedium)
                            Text("I’m organising a picnic. I need to keep track of what remains and check whether new spending puts us over budget.")
                            Text("The amounts should stay editable. Nothing to buy or book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } }
    }
    private fun consented(): Boolean = Connections.settings(this).let {
        it.getBoolean("discovery.deepseek-flash", false) && it.getBoolean("generation", false)
    }
    companion object {
        private val picnicFacts = listOf(DiscoveryFact("picnic",
            "Saturday picnic planning note. Available budget: 120 EUR. Already spent: 84 EUR. " +
            "The organiser has not reconciled the budget and needs an interactive aid with editable budget and spent amounts, " +
            "the remaining amount and a clear under/equal/over-budget status that updates when either amount changes. " +
            "All calculations use these local amounts; no connected apps, purchases or bookings are needed."))
    }
}
