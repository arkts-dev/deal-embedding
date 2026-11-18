package dev.deal.apps.organizer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deal.shell.friendlyDates
import org.json.JSONObject

/** Observe only this live owner's staging reference; cold retained records cannot enable review. */
@Composable fun PreparedSelectionReview(context: Context, experience: String) {
    val prefs = remember { context.getSharedPreferences("prepared", 0) }
    val key = "workspace.$experience"
    val baseline = remember(experience) { prefs.getString("$key.reference", "") }
    var record by remember(experience) { mutableStateOf<JSONObject?>(null) }
    var reviewing by remember(experience) { mutableStateOf(false) }
    DisposableEffect(experience) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val reference = prefs.getString("$key.reference", "")
            record = if (reference != baseline) prefs.getString(key, null)?.let(::JSONObject) else null
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    record?.let { selection ->
        Button(onClick = { reviewing = true }) { Text("Review selection") }
        if (reviewing) AlertDialog(onDismissRequest = { reviewing = false }, title = { Text("Review selection") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not booked. Complete any booking in the provider app.")
                Text(selection.optString("title"), style = MaterialTheme.typography.titleMedium)
                Text(friendlyDates(selection.optString("detail"), SHOW_ZONE))
                if (selection.optString("price").isNotEmpty()) Text(selection.getString("price"))
                if (selection.optString("deadline").isNotEmpty()) Text(friendlyDates(selection.getString("deadline"), SHOW_ZONE))
            } },
            confirmButton = { TextButton(onClick = { reviewing = false }) { Text("Close") } })
    }
}
