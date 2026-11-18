package dev.deal.apps.calendar

import android.os.Bundle
import android.content.Intent
import android.database.ContentObserver
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.unit.dp
import dev.deal.embedding.*
import dev.deal.embedding.discovery.*
import dev.deal.shell.*
import org.json.JSONObject
import java.time.*
import java.util.concurrent.Executors

/** Calendar owns event acquisition and native write confirmation, not assistance orchestration. */
@OptIn(ExperimentalMaterial3Api::class)
class PreparationActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var revision = 0L
    @Volatile private var foreground = false
    @Volatile private var selected: ShowEvent? = null
    @Volatile private var observed: ShowEvent? = null
    private var eventUi by mutableStateOf<ShowEvent?>(null)
    private var failure by mutableStateOf("")
    private var proposal by mutableStateOf<Int?>(null)
    private var confirm by mutableStateOf(false)
    private var menu by mutableStateOf(false)
    private var eventId = -1L
    private lateinit var day: LocalDate
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { if (foreground) refresh() }
    }
    private fun readEvent() = ShowAgenda(this).events(day).singleOrNull { it.id == eventId } ?: error("Event changed or removed")
    private fun facts(event: ShowEvent) = listOf(DiscoveryFact("event",
        "Gig Demo event ${event.id}: ${event.title}. Starts ${Instant.ofEpochMilli(event.from).atZone(AGENDA_ZONE)}; ends ${Instant.ofEpochMilli(event.until).atZone(AGENDA_ZONE)}. Location: ${event.location}. Start minute ${Instant.ofEpochMilli(event.from).atZone(AGENDA_ZONE).let { it.hour * 60 + it.minute }}. Notes: ${event.notes.removePrefix("Preparation demo · ")}"))
    private fun refresh(invalidate: Boolean = true) {
        val epoch = ++revision
        // Provider changes detach immediately; a lifecycle resume alone retains failure/retry state.
        if (invalidate) { selected = null; eventUi = null }
        observed = null; proposal = null; confirm = false
        worker.execute {
            try {
                val event = readEvent()
                runOnUiThread { if (foreground && epoch == revision) { selected = event; eventUi = event; failure = "" } }
            } catch (error: Throwable) {
                PreparationRecords.record(this,"event-read",JSONObject().put("status","failed").put("reason",error.toString()))
                runOnUiThread { if (foreground && epoch == revision) failure = "Preparation unavailable" }
            }
        }
    }
    private fun protocol(captured: ShowEvent): ApplicationProtocol {
        fun current(): ShowEvent = readEvent().also { check(foreground && it == captured && it == selected) { "Event changed" } }
        val capabilities = calendarCapabilities({
            val now = current(); observed = now
            Instant.ofEpochMilli(now.from).atZone(AGENDA_ZONE).let { it.hour * 60 + it.minute }
        }, { minute ->
            val now = current(); check(observed == now) { "Read the current event before proposing a reminder" }
            runOnUiThread { if (foreground && selected == now) proposal = minute }
        })
        return PreparationRecords.opportunityApi(this,listOf(capabilities))
    }
    private inner class CalendarHooks(private val captured: ShowEvent) : DemoAssistanceHooks(this@PreparationActivity) {
        override fun validateCurrent() { check(foreground && selected == captured && readEvent() == captured) { "Event changed" } }
        override fun saved(identity: String): String? = PreparationRecords.prefs(context).getString("opportunity.$eventId",null)?.let(::JSONObject)?.let {
            if (it.optString("input") == identity) it.getString("experience") else null
        }
        override fun save(identity: String, experience: String) {
            validateCurrent()
            check(PreparationRecords.prefs(context).edit().putString("opportunity.$eventId",JSONObject().put("input",identity).put("experience",experience).toString()).commit())
        }
        override fun dismissed(identity: String) = getSharedPreferences("preparation-discovery-dismissals",0).getBoolean(identity,false)
        override fun dismiss(identity: String) {
            check(getSharedPreferences("preparation-discovery-dismissals",0).edit().putBoolean(identity,true).commit())
            PreparationRecords.record(context,"opportunity-dismissal",JSONObject().put("input",identity).put("status","dismissed"))
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        eventId = intent.getLongExtra("eventId",-1L); require(eventId >= 0) { "Missing demo event" }
        day = LocalDate.parse(intent.getStringExtra("day") ?: error("Missing demo day"))
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI,true,observer)
        setContent { AppTheme(Accent.Calendar) {
            Scaffold(topBar = { TopAppBar(title = { Text(eventUi?.title ?: "Preparation",maxLines=1) },navigationIcon = {
                IconButton(onClick={finish()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            },actions = {
                Box {
                    IconButton(onClick={menu=true}) { Icon(Icons.Outlined.MoreVert,"More options") }
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("Suggestions, tools & sharing")},onClick={menu=false;startActivity(AssistanceShell.settings(this@PreparationActivity))})
                        DropdownMenuItem(text={Text("Event details")},onClick={menu=false;startActivity(Intent(this@PreparationActivity,EventDetailsActivity::class.java).putExtra("eventId",eventId).putExtra("day",day.toString()))})
                    }
                }
            }) }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
                val event = eventUi
                if (event != null) key(event) {
                    val hooks = remember { CalendarHooks(event) }
                    AssistedTask(facts=facts(event),storageName="preparation-opportunity",protocolFactory={protocol(event)},hooks=hooks,
                        review = { ReminderReview() }) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            val start = Instant.ofEpochMilli(event.from).atZone(AGENDA_ZONE)
                            val end = Instant.ofEpochMilli(event.until).atZone(AGENDA_ZONE)
                            Text("${start.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))}–${end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}")
                            if (event.location.isNotEmpty()) Text(event.location)
                        }
                    }
                } else if (failure.isNotEmpty()) Column(Modifier.padding(16.dp)) {
                    Text(failure); TextButton(onClick={refresh()}) { Text("Try again") }
                } else CircularProgressIndicator(Modifier.padding(16.dp))
            } }
            if (confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Confirm Calendar change")},
                text={Text("Add a reminder at %02d:%02d?".format((proposal ?: 0)/60,(proposal ?: 0)%60))},
                confirmButton={TextButton(onClick={
                    confirm=false
                    val origin=observed; val minute=proposal
                    worker.execute {
                        try {
                            check(foreground && origin != null && origin == selected && origin == readEvent() && minute != null)
                            check(Connections.settings(this@PreparationActivity).getBoolean("grant.host/calendar",false))
                            ShowAgenda(this@PreparationActivity).confirmReminder(origin,minute)
                            runOnUiThread { proposal=null }
                        } catch(error:Throwable) {
                            PreparationRecords.record(this@PreparationActivity,"reminder",JSONObject().put("status","failed").put("reason",error.toString()))
                            runOnUiThread { proposal=null;failure="Couldn’t save reminder" }
                        }
                    }
                }) {Text("Save reminder")}},dismissButton={TextButton(onClick={confirm=false}) {Text("Keep unchanged")}})
        } }
    }
    @Composable private fun ReminderReview() {
        proposal?.let { minute -> OutlinedButton(onClick={confirm=true}) { Text("Review reminder · %02d:%02d".format(minute/60,minute%60)) } }
        if (failure.isNotEmpty()) Text(failure)
    }
    override fun onResume() { super.onResume(); foreground=true; refresh(false) }
    override fun onPause() { foreground=false;revision++;observed=null;proposal=null;confirm=false;menu=false;super.onPause() }
    override fun onDestroy() { foreground=false;contentResolver.unregisterContentObserver(observer);worker.shutdown();super.onDestroy() }
}
