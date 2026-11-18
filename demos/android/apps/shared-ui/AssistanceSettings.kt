package dev.deal.shell

import dev.deal.embedding.*
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.deal.embedding.CapabilityContract
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun appAccent(packageName: String) = when (packageName.substringAfterLast('.')) {
    "calendar" -> Accent.Calendar
    "organizer" -> Accent.Organizer
    "pizza" -> Accent.Pizza
    "todo" -> Accent.Todo
    else -> Accent.Rental
}

@Composable fun AssistanceSettingsButton(localModule: String = "", storageName: String = "assistance") {
    val context = LocalContext.current
    IconButton(onClick = { context.startActivity(AssistanceShell.settings(context).putExtra("localModule", localModule).putExtra("storage", storageName)) }) { Icon(Icons.Outlined.Settings, "Settings") }
}

@OptIn(ExperimentalMaterial3Api::class)
class AssistanceSettingsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AppTheme(appAccent(packageName)) {
            val settings = remember { Connections.settings(this) }
            val sharing = remember { getSharedPreferences("provider", 0) }
            var discover by remember { mutableStateOf(settings.getBoolean(discoveryPreference(), AssistanceBuild.discoveryBackend == "nano")) }
            var generate by remember { mutableStateOf(settings.getBoolean("generation", false)) }
            var shared by remember { mutableStateOf(sharing.getBoolean("allowed", false)) }
            var contracts by remember { mutableStateOf<List<CapabilityContract>>(emptyList()) }
            var grants by remember { mutableStateOf(settings.all.filter { it.key.startsWith("grant.") }.mapValues { it.value == true }) }
            var problem by remember { mutableStateOf("") }
            var resetConfirm by remember { mutableStateOf(false) }
            var resetting by remember { mutableStateOf(false) }
            var resetResult by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            BackHandler(enabled = resetting) {}
            LaunchedEffect(Unit) { withContext(Dispatchers.IO) {
                runCatching { assistanceProtocol(this@AssistanceSettingsActivity).use { it.refresh() } }
                    .onSuccess { contracts = it }.onFailure { problem = "Couldn’t find connected apps" }
            } }
            Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(enabled = !resetting, onClick = { finish() }) { Text("Back") } }) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(enabled = !resetting, onClick = { startActivity(AssistanceShell.inspect(this@AssistanceSettingsActivity)) }) { Text("Inspect") }
                    Text("Suggestions", style = MaterialTheme.typography.titleLarge)
                    SettingSwitch(AssistanceBuild.discoveryLabel, discover, AssistanceBuild.discoveryDetail) { discover = it; check(settings.edit().putBoolean(discoveryPreference(), it).commit()) }
                    SettingSwitch("Tailored tools", generate, "Open sends the suggestion and supporting content to the configured code service. It does not book or purchase anything.") { generate = it; check(settings.edit().putBoolean("generation", it).commit()) }
                    HorizontalDivider()
                    Text("Sharing", style = MaterialTheme.typography.titleLarge)
                    SettingSwitch("Share with connected apps", shared, "Allow trusted apps to read shared records and submit proposals. Writes still require native confirmation. Turning this off keeps existing records.") { shared = it; check(sharing.edit().putBoolean("allowed", it).commit()) }
                    HorizontalDivider()
                    Text("Tool access", style = MaterialTheme.typography.titleLarge)
                    Text("Choose which connected apps tools may use. This does not turn on sharing in those apps. Revoking access does not delete their records.")
                    if (problem.isNotEmpty()) Text(problem)
                    (intent.getStringExtra("localModule")?.takeIf { it.isNotEmpty() } ?: when (packageName.substringAfterLast('.')) {
                        "calendar" -> "host/calendar"
                        "organizer" -> "host/organizer"
                        else -> null
                    })?.let { module ->
                        val key = "grant.$module"
                        SettingSwitch("${Connections.name(module)} review", grants[key] == true) { value ->
                            check(settings.edit().putBoolean(key, value).commit()); grants = grants + (key to value)
                        }
                    }
                    contracts.forEach { contract ->
                        val key = "grant.${contract.module}"
                        SettingSwitch(Connections.name(contract.module), grants[key] == true) { value ->
                            check(settings.edit().putBoolean(key, value).commit()); grants = grants + (key to value)
                        }
                    }
                    HorizontalDivider()
                    Text("Saved assistance", style = MaterialTheme.typography.titleLarge)
                    Text("Delete generated tools and their retained edits to start fresh. Records, permissions, sharing, tool access and retained evidence stay unchanged.")
                    OutlinedButton(enabled = !resetting, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { resetConfirm = true }) { Text(if (resetting) "Deleting…" else "Delete all generated experiences") }
                    if (resetResult.isNotEmpty()) Text(resetResult)
                }
            }
            if (resetConfirm) AlertDialog(onDismissRequest = { if (!resetting) resetConfirm = false },
                title = { Text("Delete all generated experiences?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Remove", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text("Saved tools, their retained edits, task links and suggestion dismissals in this app. This cannot be undone.")
                    Text("Keep", style = MaterialTheme.typography.titleSmall)
                    Text("Your records, permissions, sharing and tool-access choices, and diagnostic evidence.")
                } },
                confirmButton = { TextButton(enabled = !resetting, onClick = {
                    resetConfirm = false; resetting = true; resetResult = ""
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { runCatching { AssistanceShell.reset(this@AssistanceSettingsActivity) } }
                        resetting = false
                        resetResult = if (outcome.isSuccess) "Generated experiences deleted" else "Couldn’t finish deleting assistance. Evidence and access choices were kept; review Inspect before trying again."
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("Cancel") } })
        } }
    }
}

@Composable private fun SettingSwitch(label: String, value: Boolean, description: String = "", change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = change).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, null)
    }
}
