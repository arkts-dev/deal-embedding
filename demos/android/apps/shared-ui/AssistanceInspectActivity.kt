package dev.deal.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deal.embedding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Standard presentation of public observation. No storage access, trace joins or session driving. */
@OptIn(ExperimentalMaterial3Api::class)
class AssistanceInspectActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val storageName = intent.getStringExtra("storage") ?: AssistanceShell.storage(this)
        val trace = intent.getStringExtra("trace").orEmpty()
        val since = intent.getLongExtra("since", 0L)
        setContent { AppTheme(appAccent(packageName)) {
            var tab by remember { mutableStateOf("Activity") }
            var page by remember { mutableStateOf(EventPage(emptyList(), "", false)) }
            var sources by remember { mutableStateOf<List<Pair<Experience, Source>>>(emptyList()) }
            var scope by remember { mutableStateOf(intent.getStringExtra("experience") ?: "") }
            var requestScope by remember { mutableStateOf(trace.isNotEmpty() || since > 0) }
            var capture by remember { mutableStateOf(false) }
            var captureChange by remember { mutableStateOf<Boolean?>(null) }
            var consent by remember { mutableStateOf(false) }
            var detail by remember { mutableStateOf<String?>(null) }
            var selected by remember { mutableStateOf<JSONObject?>(null) }
            var error by remember { mutableStateOf("") }
            var appRecords by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
            LaunchedEffect(captureChange) { withContext(Dispatchers.IO) {
                runCatching { assistanceProtocol(this@AssistanceInspectActivity, storageName).use { api ->
                    captureChange?.let(api::capture)
                    page = api.retainedEvents(); capture = api.capturesContent()
                    sources = api.saved().map { it to api.source(it) }
                    appRecords = AssistanceShell.records(this@AssistanceInspectActivity)
                } }.onFailure { error = "Evidence unavailable; nothing was retried." }
            } }
            LaunchedEffect(selected) { selected?.let { event ->
                detail = withContext(Dispatchers.IO) {
                    runCatching { assistanceProtocol(this@AssistanceInspectActivity, storageName).use { api ->
                        val artifact = event.optString("artifact").takeIf { it.isNotEmpty() }?.let(api::artifact)
                        (artifact?.let { "${it.status}\n${readableLogDetail(it.text)}" } ?: "Content was not captured or has been evicted.") + "\n\nExact metadata\n" + event.toString(2)
                    } }.getOrElse { "Evidence unavailable: ${it.javaClass.simpleName}" }
                }
            } }
            val evidence = page.inspection(experiences = setOf(scope),
                traces = if (requestScope && scope.isEmpty() && trace.isNotEmpty()) setOf(trace) else emptySet(),
                since = if (requestScope && scope.isEmpty() && trace.isEmpty()) since else 0L)
            val events = evidence.events.reversed()
            Scaffold(topBar = { TopAppBar(title = { Text("Inspect") }, navigationIcon = { TextButton(onClick = { finish() }) { Text("Back") } }) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Observed activity, not hidden AI reasoning", style = MaterialTheme.typography.bodySmall)
                    if (evidence.incomplete) Text("Retained evidence is incomplete; missing outcomes are not success.")
                    if (error.isNotEmpty()) Text(error)
                    TextButton(onClick = { if (capture) captureChange = false else consent = true }) { Text(if (capture) "Content capture ON · turn off" else "Capture future content") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Activity", "Dialogue", "Source").forEach { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(scope.isEmpty() && !requestScope, { scope = ""; requestScope = false }, label = { Text("All assistance") })
                        if (trace.isNotEmpty() || since > 0) FilterChip(scope.isEmpty() && requestScope, { scope = ""; requestScope = true }, label = { Text("This request") })
                        sources.forEach { (experience, _) -> FilterChip(scope == experience.id, { scope = experience.id }, label = { Text(experience.title) }) }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tab) {
                            "Source" -> sources.filter { scope.isEmpty() || it.first.id == scope }.forEach { (experience, source) -> item {
                                Text(experience.title, style = MaterialTheme.typography.titleMedium)
                                Text("${experience.origin} · ${experience.attempts} source attempts. Saved replay is not fresh inference.")
                                TextButton(onClick = { detail = source.deal }) { Text("View .deal") }
                                TextButton(onClick = { detail = source.dealui }) { Text("View .dealui") }
                            } }
                            "Dialogue" -> {
                                item { Text("Actual requests, replies and compiler feedback. Capture is not retroactive.") }
                                val exchanges = dialogueExchanges(events)
                                if (exchanges.isEmpty()) item { Text("No retained exchange.") }
                                items(exchanges, key = { it.key }) { exchange -> Column {
                                    Text(exchange.stage, style = MaterialTheme.typography.labelMedium)
                                    exchange.events.forEach { entry -> EvidenceEntry(dialogueRole(entry), entry) { selected = entry } }
                                } }
                            }
                            else -> {
                                if (scope.isEmpty() && !requestScope) items(appRecords) { record ->
                                    OutlinedCard(onClick = { detail = record.toString(2) }, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("App decision · ${record.optString("stage")} · ${record.optString("status")}", style = MaterialTheme.typography.titleSmall)
                                            Text(dateTimeLabel(record.optLong("at"), java.time.ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (events.isEmpty()) item { Text("No retained activity.") }
                                items(events.take(200)) { entry -> EvidenceEntry(activitySummary(entry), entry) { selected = entry } }
                            }
                        }
                    }
                }
            }
            if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Capture private content?") }, text = { Text("Store future prompts, replies and produced snapshots privately within size limits. Capture is optional and not retroactive. Credentials are excluded.") }, confirmButton = { TextButton(onClick = { captureChange = true; consent = false }) { Text("Enable") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
            detail?.let { text -> AlertDialog(onDismissRequest = { detail = null; selected = null }, title = { Text("Retained detail · untrusted content") }, text = { Text(text, Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { detail = null; selected = null }) { Text("Close") } }) }
        } }
    }
}

@Composable private fun EvidenceEntry(title: String, event: JSONObject, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(dateTimeLabel(event.optString("time").toLongOrNull() ?: 0, java.time.ZoneId.systemDefault()) +
            " · ${event.optString("outcome")} · ${event.optString("code")} · ${event.optLong("durationMs")}ms", style = MaterialTheme.typography.bodySmall)
    } }
}
