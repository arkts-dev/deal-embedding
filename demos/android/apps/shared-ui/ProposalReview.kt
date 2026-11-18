package dev.deal.shell

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Native review is outside generated content; the provider validates and applies its own domain change. */
@Composable fun ProposalReview(context: Context, prefix: String, confirm: (JSONObject) -> JSONObject) {
    val prefs = remember { context.getSharedPreferences("proposals", 0) }
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var error by remember { mutableStateOf("") }
    val pending = remember(version) { prefs.all.entries.mapNotNull { (key, value) ->
        if (!key.startsWith("$prefix:") || value !is String) null else runCatching { JSONObject(value) }.getOrNull()?.takeIf { it.optString("state") == "pending" }?.let { key to it }
    } }
    if (pending.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Awaiting your review", style = MaterialTheme.typography.titleMedium)
        pending.forEach { entry -> OutlinedButton(onClick = { selected = entry; error = "" }) { Text(entry.second.optString("title").ifBlank { "Review proposed ${if (prefix == "pizza") "order" else "reservation"}" }) } }
    }
    selected?.let { (key, proposal) -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Confirm ${when (prefix) { "todo" -> "task"; "pizza" -> "demo order"; else -> "demo reservation" }}?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            proposal.keys().asSequence().filter { it !in setOf("state", "proposalId") }.forEach { field -> Text("${field.replaceFirstChar { it.uppercase() }}: ${proposal.get(field)}") }
            if (prefix != "todo") Text("Demo only. No real purchase, booking or delivery.")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val current = JSONObject(prefs.getString(key, "{}")); check(current.toString() == proposal.toString() && current.getString("state") == "pending")
            val outcome = confirm(current)
            check(prefs.edit().putString(key, JSONObject(proposal.toString()).put("state", outcome.getString("state"))
                .apply { outcome.keys().forEach { put(it, outcome.get(it)) } }.toString()).commit())
            selected = null; version++
        } catch (_: Exception) { error = "Couldn’t apply this proposal. Check the details and try again." }
    }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = {
        check(prefs.edit().putString(key, JSONObject(proposal.toString()).put("state", "declined").put("detail", "Declined in the owning app").toString()).commit())
        selected = null; version++
    }) { Text("Decline") } }) }
}

fun proposalOutcome(context: Context, prefix: String, id: String, reference: String): JSONObject {
    val record = context.getSharedPreferences("proposals", 0).getString("$prefix:$id", null)?.let(::JSONObject)
    return JSONObject().put("state", record?.optString("state") ?: "unknown").put(reference, record?.optString(reference) ?: "")
        .put("detail", record?.optString("detail") ?: "No such proposal")
}
