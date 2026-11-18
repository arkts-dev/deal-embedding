package dev.deal.apps.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.deal.shell.*
import dev.deal.embedding.discovery.DiscoveryFact
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
class TodoActivity : ComponentActivity() {
    private val store by lazy { TaskStore(this) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.hasExtra("demoTask")) {
            val day = LocalDate.now(TASK_ZONE).plusDays(1)
            store.save(store.tasks().filter { it.show != "Preparation demo" } + listOf(
                Task(store.newId(), intent.getStringExtra("demoTask")!!, "Me", TaskStore.at(day, 12), show = "Preparation demo"),
                Task(store.newId(), "Water balcony plants", "Me", TaskStore.at(day, 18), show = "Preparation demo")))
        }
        var tasks by mutableStateOf(store.tasks())
        var filter by mutableStateOf("Open")
        var open by mutableStateOf<Task?>(null)
        var adding by mutableStateOf(false)
        var access by mutableStateOf(false)
        fun commit(value: List<Task>) { store.save(value); tasks = value }
        setContent {
            BackHandler(enabled = access || open != null || adding) { access = false; open = null; adding = false }
            AppTheme(Accent.Todo) {
                Surface(Modifier.fillMaxSize()) {
                    Scaffold(topBar = {
                        TopAppBar(title = {
                            Column { Text(if (access) "Review" else "Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (access) "Task proposals" else "Your tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }, actions = { AssistanceSettingsButton(); IconButton(onClick = { access = false; adding = true }) { Icon(Icons.Outlined.Add, "Add task") } })
                    }, bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected = !access && open == null && !adding, onClick = { open = null; adding = false; access = false }, icon = { Icon(Icons.Outlined.Checklist, null) }, label = { Text("Tasks") })
                            NavigationBarItem(selected = access, onClick = { access = true; open = null; adding = false }, icon = { Icon(Icons.Outlined.Shield, null) }, label = { Text("Review") })
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            when {
                                access -> Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    ProposalReview(this@TodoActivity, "todo") { proposal ->
                                        val id = store.newId()
                                        val checks = proposal.getJSONArray("checks")
                                        commit(tasks + Task(id, proposal.getString("title"), proposal.getString("assignee"), Instant.parse(proposal.getString("due")).toEpochMilli(),
                                            proposal.getString("location"), proposal.getString("instructions"), checklist = (0 until checks.length()).map { ChecklistItem(checks.getString(it)) }))
                                        org.json.JSONObject().put("state", "confirmed").put("taskId", id).put("detail", "Task saved after native confirmation")
                                    }
                                }
                                adding -> TaskEditor(null) { created -> commit(tasks + created); adding = false }
                                open != null -> AssistedTask(listOf(DiscoveryFact("task", open!!.let { task ->
                                    "Synthetic demo task ${task.id}: ${task.title}. Assigned to ${task.assignee}. Due ${Instant.ofEpochMilli(task.due)} in Europe/Berlin. Location: ${task.location}. Show: ${task.show}. Instructions: ${task.instructions}. Checks: ${task.checklist.joinToString { it.text + if (it.done) " (done)" else " (open)" }}. Complete: ${task.complete}."
                                })), title = open!!.title) {
                                    TaskDetail(open!!) { updated ->
                                        val next = if (updated == null) tasks.filterNot { it.id == open!!.id } else tasks.map { if (it.id == updated.id) updated else it }
                                        commit(next); open = updated
                                    }
                                }
                                else -> TaskList(tasks, filter, { filter = it }) { open = it }
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable private fun TaskList(tasks: List<Task>, filter: String, onFilter: (String) -> Unit, onOpen: (Task) -> Unit) {
    val visible = tasks.filter { when (filter) { "Open" -> !it.complete; "Completed" -> it.complete; else -> true } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Open", "Completed", "All").forEach { Chip(it, filter == it) { onFilter(it) } }
            }
        }
        if (visible.isEmpty()) item { Text(if (tasks.isEmpty()) "No tasks yet. Add a task to get started." else "No ${filter.lowercase()} tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val grouped = visible.groupBy { it.show.ifEmpty { "Other tasks" } }
        grouped.forEach { (show, list) ->
            item { Text(show, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(list.sortedBy { it.due }) { task -> TaskCard(task) { onOpen(task) } }
        }
    }
}

@Composable private fun TaskCard(task: Task, onClick: () -> Unit) {
    val overdue = !task.complete && task.due in 1 until System.currentTimeMillis()
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (task.complete) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
            Text("${task.assignee} · due ${time(task.due)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.checklist.isNotEmpty()) {
                Meter(task.progress)
                Text("${task.checklist.count { it.done }} of ${task.checklist.size} checks done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (overdue) StatusPill("Overdue", Tone.Attention)
                if (task.complete) StatusPill("Completed", Tone.Settled)
            }
        }
    }
}

@Composable private fun TaskDetail(task: Task, onChange: (Task?) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${task.assignee} · due ${time(task.due)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Panel {
            if (task.show.isNotEmpty()) KeyValue("Show", task.show)
            if (task.location.isNotEmpty()) KeyValue("Location", task.location)
            KeyValue("Due", time(task.due))
            if (task.instructions.isNotEmpty()) Text(task.instructions, style = MaterialTheme.typography.bodyMedium)
            task.links.forEach { KeyValue("Linked", it) }
        }
        if (task.checklist.isNotEmpty()) {
            Panel {
                Text("Checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                task.checklist.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(item.done, { checked ->
                            val next = task.checklist.toMutableList().also { it[index] = item.copy(done = checked) }
                            onChange(task.copy(checklist = next))
                        })
                        Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Completion needs every check.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onChange(task.copy(complete = !task.complete)) }, enabled = task.complete || task.canComplete, shape = RoundedCornerShape(18.dp)) { Text(if (task.complete) "Reopen" else "Mark complete") }
            TextButton(onClick = { onChange(null) }) { Text("Delete task") }
        }
        Text("Deleting a task does not cancel a reservation, order or calendar activity.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun TaskEditor(existing: Task?, onCreate: (Task) -> Unit) {
    var title by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("15") }
    var location by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<List<String>>(emptyList()) }
    var checkDraft by remember { mutableStateOf("") }
    val store = TaskStore(androidx.compose.ui.platform.LocalContext.current)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("New task", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        OutlinedTextField(assignee, { assignee = it }, label = { Text("Assignee") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        OutlinedTextField(minutes, { minutes = it }, label = { Text("Preparation minutes") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        OutlinedTextField(location, { location = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Panel {
            Text("Checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            checks.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(checkDraft, { checkDraft = it }, label = { Text("Add a check") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                FilledTonalIconButton(onClick = { if (checkDraft.isNotBlank()) { checks = checks + checkDraft; checkDraft = "" } }) { Icon(Icons.Outlined.Add, "Add check") }
            }
        }
        Button(onClick = {
            val count = minutes.toIntOrNull() ?: 15
            onCreate(Task(store.newId(), title.ifBlank { "Task" }, assignee.ifBlank { "Unassigned" }, System.currentTimeMillis() + count * 60_000L,
                location, instructions, "", checks.map { ChecklistItem(it) }, minutes = count))
        }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Create task") }
    }
}

private fun time(epochMillis: Long): String = dev.deal.shell.dateTimeLabel(epochMillis, TASK_ZONE)
