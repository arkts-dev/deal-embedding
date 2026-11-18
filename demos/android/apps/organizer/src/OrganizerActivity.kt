package dev.deal.apps.organizer

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.deal.embedding.*
import dev.deal.embedding.discovery.DiscoveryFact
import dev.deal.shell.*

private enum class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("Overview", Icons.Outlined.Dashboard), Riders("Riders", Icons.Outlined.Assignment), Resources("Resources", Icons.Outlined.Inventory2), Fulfilment("Fulfilment", Icons.Outlined.Checklist)
}

class OrganizerActivity : ComponentActivity() {
    private val store by lazy { ShowStore(this) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        var show by mutableStateOf(store.load())
        fun update(block: (ShowState) -> Unit) {
            val current = show ?: return
            // Publish a new plan identity: mutating a shared object can leave child
            // composables showing stale checks, allocations and filtered lists.
            val next = ShowState.parse(current.toJson().toString())
            block(next); store.save(next); show = next
        }
        setContent {
            AppTheme(Accent.Organizer) {
                var destination by remember { mutableStateOf(Destination.Overview) }
                var selectedAct by remember { mutableStateOf<String?>(null) }
                var selectedDepartment by remember { mutableStateOf<String?>(null) }
                var selectedRequirement by remember { mutableStateOf<String?>(null) }
                BackHandler(enabled = selectedRequirement != null) { selectedRequirement = null }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            Column {
                                OrganizerTopBar(show, onInitialize = { show = store.initialize() }, onInspect = if (selectedRequirement != null) null else ({
                                    startActivity(AssistanceShell.inspect(this@OrganizerActivity))
                                }))
                            }
                        },
                        bottomBar = {
                            if (show != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                Destination.entries.forEach { item ->
                                    NavigationBarItem(selected = destination == item, onClick = { destination = item; selectedRequirement = null }, icon = { Icon(item.icon, null) }, label = { Text(item.label) })
                                }
                            }
                        },
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            when {
                                show == null -> EmptyPlan(onInitialize = { show = store.initialize() })
                                selectedRequirement != null -> AssistedTask(listOf(DiscoveryFact("requirement", show!!.requirement(selectedRequirement!!)!!.let { r ->
                                    "Show ${show!!.title}, ${show!!.date} at ${show!!.venue}, ${show!!.address}. ${r.act}: ${r.quantity} × ${r.name}. Specification ${r.specification}. Needed from ${java.time.Instant.ofEpochMilli(r.from)} until ${java.time.Instant.ofEpochMilli(r.until)} at ${r.location}. Status ${r.summary}. Notes ${r.notes}."
                                })), storageName = "organizer-protocol", title = show!!.requirement(selectedRequirement!!)!!.name, protocolFactory = { OrganizerHost(it).embedding },
                                    review = { experience -> PreparedSelectionReview(this@OrganizerActivity, experience.id) }) { RequirementDetail(
                                    show = show!!, requirementId = selectedRequirement!!,
                                    onBack = { selectedRequirement = null },
                                    onEdit = { updated -> update { it.requirements = it.requirements.map { r -> if (r.id == updated.id) updated else r } } },
                                    onVerify = { id -> update { s -> s.requirements = s.requirements.map { r -> if (r.id == id) r.copy(verified = !r.verified) else r } } },
                                ) }
                                else -> when (destination) {
                                    Destination.Overview -> OverviewScreen(state = show!!, onOpenRequirement = { selectedAct = null; selectedRequirement = it }, onOpenRiders = { department -> selectedAct = null; selectedDepartment = department; destination = Destination.Riders })
                                    Destination.Riders -> RidersScreen(state = show!!, selectedAct, selectedDepartment, onSelectDepartment = { selectedDepartment = it }, onSelectAct = { selectedAct = it }, onOpenRequirement = { selectedRequirement = it })
                                    Destination.Resources -> ResourcesScreen(state = show!!, onResolve = { allocation, agreed -> update { s ->
                                        val resource = s.allocations.first { it.id == allocation }.resourceId
                                        s.allocations = s.allocations.map { if (it.resourceId == resource) it.copy(agreed = agreed) else it }
                                    } })
                                    Destination.Fulfilment -> FulfilmentScreen(state = show!!, onVerify = { id -> update { s -> s.requirements = s.requirements.map { r -> if (r.id == id) r.copy(verified = !r.verified) else r } } }, onOpenRequirement = { selectedRequirement = it })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OrganizerTopBar(show: ShowState?, onInitialize: () -> Unit, onInspect: (() -> Unit)?) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = {
            Column {
                Text(show?.title ?: "Gig Organizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(show?.let { "${it.venue} · ${dateLabel(it.date)}" } ?: "No show yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            AssistanceSettingsButton("host/organizer", "organizer-protocol")
            if (show == null) TextButton(onClick = onInitialize) { Text("Initialize demo show") }
            if (onInspect != null) TextButton(onClick = onInspect) { Icon(Icons.Outlined.Timeline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Inspect") }
        },
    )
}

@Composable private fun EmptyPlan(onInitialize: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyState("No show planned yet", "Initialize the demo show to plan one gig, one venue and two acts.", art = { Art.Stage(170.dp) })
        Button(onClick = onInitialize, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 16.dp)) { Text("Initialize demo show") }
    }
}

@Composable private fun OverviewScreen(state: ShowState, onOpenRequirement: (String) -> Unit, onOpenRiders: (String) -> Unit) {
    val conflicts = state.conflicts()
    val attention = state.requirements.filter { !it.ready }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Needs attention", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${attention.size} outstanding needs · ${state.requirements.count { it.ready }} checked and ready", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(attention, key = { it.id }) { requirement ->
            AssistancePanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (requirement.coverage == Coverage.Conflict) Icons.Outlined.WarningAmber else Icons.Outlined.RadioButtonUnchecked, null)
                    Column(Modifier.weight(1f)) {
                        Text(requirement.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${requirement.act} · ${requirement.location}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${dateLabel(state.date)} · ${state.label(requirement.from)}–${state.label(requirement.until)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = { onOpenRequirement(requirement.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Review requirement")
                }
            }
        }
        if (state.requirements.any { it.ready }) item {
            Section("Ready") { state.requirements.filter { it.ready }.forEach { RequirementRow(it) { onOpenRequirement(it.id) } } }
        }
        item { Section("Timeline") { ShowTimeline(state, conflicts) } }
        item { Section("Departments") { } }
        items(state.requirements.map { it.department }.distinct()) { department ->
            val items = state.requirements.filter { it.department == department }
            DepartmentCard(department, items) { onOpenRiders(department) }
        }
    }
}

@Composable private fun ShowTimeline(show: ShowState, conflicts: List<Allocation>) {
    val entries = buildList {
        add(Triple("Soundcheck", show.at(16, 0), "Glass Harbour"))
        add(Triple("Soundcheck", show.at(17, 0), "Static Bloom"))
        add(Triple("Doors", show.at(19, 0), show.venue))
        add(Triple("Performance", show.at(20, 0), "Glass Harbour"))
        add(Triple("Performance", show.at(21, 15), "Static Bloom"))
        show.requirements.filter { it.name.contains("pizza") }.forEach { add(Triple("Delivery", it.until, "Dressing room")) }
    }.sortedBy { it.second }
    Panel {
        entries.forEachIndexed { index, (kind, at, who) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(show.label(at), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(60.dp), fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text(who, style = MaterialTheme.typography.bodyLarge)
                    Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (kind != "Delivery") Icon(if (kind == "Doors") Icons.Outlined.DoorFront else Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        if (conflicts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text("Two allocations overlap: ${show.label(conflicts.first().from)}–${show.label(conflicts.first().until)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun DepartmentCard(department: String, items: List<Requirement>, onClick: () -> Unit) {
    val settled = items.count { it.ready }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(department, style = MaterialTheme.typography.titleLarge)
                Text("$settled of ${items.size} checked and ready", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text("Open") }
        }
        Meter(if (items.isEmpty()) 0f else settled.toFloat() / items.size)
    }
}

@Composable private fun RidersScreen(state: ShowState, selectedAct: String?, selectedDepartment: String?, onSelectDepartment: (String?) -> Unit, onSelectAct: (String?) -> Unit, onOpenRequirement: (String) -> Unit) {
    var filter by remember { mutableStateOf("All") }
    fun matches(r: Requirement) = (selectedAct == null || r.act == selectedAct) && (selectedDepartment == null || r.department == selectedDepartment) && when (filter) {
        "Missing" -> r.coverage == Coverage.Missing
        "Conflicts" -> r.coverage == Coverage.Conflict
        "Awaiting approval" -> r.commitment == Commitment.Proposed
        else -> true
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OrganizerHeading("Riders", "Requirements and what still needs arranging.")
            if (selectedDepartment != null) TextButton(onClick = { onSelectDepartment(null) }) { Text("$selectedDepartment · Show all departments") }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("All", "Missing", "Conflicts", "Awaiting approval").forEach { Chip(it, filter == it) { filter = it } }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Both acts", selectedAct == null) { onSelectAct(null) }
                state.acts().forEach { Chip(it, selectedAct == it) { onSelectAct(it) } }
            }
        }
        if (state.requirements.none { matches(it) }) item { Text("No requirements match these filters.") }
        state.acts().filter { act -> state.requirements.any { it.act == act && matches(it) } }.forEach { act ->
            item { Text(act, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            state.groups(act).filter { group -> state.requirements.any { it.act == act && it.group == group && matches(it) } }.forEach { group ->
                item {
                    RiderGroupCard(state, act, group, filter, onOpenRequirement)
                }
            }
        }
    }
}

@Composable private fun RiderGroupCard(show: ShowState, act: String, group: String, filter: String, onOpenRequirement: (String) -> Unit) {
    val requirements = show.requirements.filter { it.act == act && it.group == group }.filter {
        when (filter) {
            "Missing" -> it.coverage == Coverage.Missing
            "Conflicts" -> it.coverage == Coverage.Conflict
            "Awaiting approval" -> it.commitment == Commitment.Proposed
            else -> true
        }
    }
    if (requirements.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(requirements.first().department, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if (show.groupCovered(act, group)) "Complete" else "Incomplete", if (show.groupCovered(act, group)) Tone.Settled else Tone.Attention)
            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse $group" else "Expand $group") }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                requirements.forEach { requirement ->
                    RequirementRow(requirement) { onOpenRequirement(requirement.id) }
                }
            }
        }
    }
}

@Composable private fun RequirementRow(requirement: Requirement, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (requirement.coverage == Coverage.Covered) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (requirement.coverage == Coverage.Covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(if (requirement.quantity > 1) "${requirement.quantity} × ${requirement.name}" else requirement.name, style = MaterialTheme.typography.bodyLarge)
                Text(requirement.summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!requirement.mandatory) StatusPill("Negotiable", Tone.Neutral)
        }
    }
}

@Composable private fun ResourcesScreen(state: ShowState, onResolve: (String, Boolean) -> Unit) {
    var department by remember { mutableStateOf("Sound") }
    val conflicts = state.conflicts()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OrganizerHeading("Stock and allocations", "Venue equipment, contributions and conflicts.") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.requirements.map { it.department }.distinct().forEach { Chip(it, department == it) { department = it } }
            }
        }
        if (conflicts.isNotEmpty() && department == "Backline") {
            item {
                Panel {
                    Text("Allocation conflict", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text("Both acts need the venue bass amplifier for overlapping preparation windows.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val grouped = conflicts.groupBy { it.resourceId }
                    grouped.forEach { (resourceId, list) ->
                        Text(state.resource(resourceId)?.name ?: resourceId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        list.sortedBy { it.from }.forEach { allocation ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(allocation.act, style = MaterialTheme.typography.bodyMedium)
                                Text("${state.label(allocation.from)}–${state.label(allocation.until)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    val agreed = conflicts.all { it.agreed }
                    Text(if (agreed) "Agreement recorded. The allocated windows still overlap; neither requirement is marked covered." else "Record an agreement only after both acts accept it. This does not change the allocated windows or mark equipment covered.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { conflicts.firstOrNull()?.let { onResolve(it.id, !agreed) } }, shape = RoundedCornerShape(18.dp)) { Text(if (agreed) "Undo agreement" else "Record agreement") }
                }
            }
        }
        items(state.resources.filter { it.department == department }) { resource ->
            val allocated = state.allocations.filter { it.resourceId == resource.id }
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(resource.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(resource.specification, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill("${resource.quantity} available", Tone.Neutral)
                }
                KeyValue("Source", resource.source)
                if (resource.notes.isNotEmpty()) KeyValue("Notes", resource.notes)
                if (allocated.isNotEmpty()) {
                    Text("Allocated", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    allocated.sortedBy { it.from }.forEach { allocation ->
                        KeyValue(allocation.act, "${state.label(allocation.from)}–${state.label(allocation.until)}")
                    }
                }
            }
        }
    }
}

@Composable private fun FulfilmentScreen(state: ShowState, onVerify: (String) -> Unit, onOpenRequirement: (String) -> Unit) {
    var view by remember { mutableStateOf("By type") }
    val outstanding = state.requirements.filter { it.coverage != Coverage.Covered || !it.verified }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OrganizerHeading("Event checklist", "Check each requirement on site before marking it ready.") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("By type", "By person", "By deadline").forEach { Chip(it, view == it) { view = it } }
            }
        }
        if (outstanding.isEmpty()) {
            item { Panel { Text("Every requirement is checked and ready.", style = MaterialTheme.typography.titleMedium); Text("The show plan is complete.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        val ordered = when (view) {
            "By person" -> outstanding.sortedWith(compareBy({ it.act }, { it.until }))
            "By type" -> outstanding.sortedWith(compareBy({ it.department }, { it.until }))
            else -> outstanding.sortedBy { it.until }
        }
        items(ordered, key = { it.id }) { requirement ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(requirement.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (view == "By person") "${requirement.act} · ${requirement.group}" else "${requirement.act} · ${requirement.department}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(requirement.summary, if (requirement.coverage == Coverage.Conflict) Tone.Attention else Tone.Progress)
                }
                KeyValue("Required by", dateTimeLabel(requirement.until, SHOW_ZONE))
                KeyValue("Location", requirement.location)
                if (view == "By deadline") KeyValue("Group", requirement.group)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onVerify(requirement.id) }, shape = RoundedCornerShape(18.dp), enabled = requirement.coverage == Coverage.Covered) {
                        Text(if (requirement.verified) "Undo check" else "Record check")
                    }
                    OutlinedButton(onClick = { onOpenRequirement(requirement.id) }, shape = RoundedCornerShape(18.dp)) { Text("Open") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RequirementDetail(show: ShowState, requirementId: String, onBack: () -> Unit, onEdit: (Requirement) -> Unit, onVerify: (String) -> Unit) {
    val requirement = show.requirement(requirementId) ?: return
    var editing by remember(requirementId) { mutableStateOf(false) }
    var draft by remember(requirementId, requirement) { mutableStateOf(requirement) }
    var quantityText by remember(requirementId, requirement) { mutableStateOf(requirement.quantity.toString()) }
    val validDraft = draft.name.isNotBlank() && (quantityText.toIntOrNull()?.let { it in 1..999 } == true)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
                Column {
                    Text(requirement.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${requirement.act} · ${requirement.department}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (editing) {
            item {
                Panel {
                    Text("Edit requirement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    listOf("Name" to draft.name, "Quantity" to quantityText, "Specification" to draft.specification, "Location" to draft.location, "Substitutions" to draft.substitutions, "Notes" to draft.notes, "Rider reference" to draft.riderRef).forEachIndexed { index, (label, value) ->
                        OutlinedTextField(
                            value = value, onValueChange = { text ->
                                draft = when (index) {
                                    0 -> draft.copy(name = text); 1 -> { quantityText = text; draft.copy(quantity = text.toIntOrNull() ?: draft.quantity) }
                                    2 -> draft.copy(specification = text); 3 -> draft.copy(location = text)
                                    4 -> draft.copy(substitutions = text); 5 -> draft.copy(notes = text); else -> draft.copy(riderRef = text)
                                }
                            }, label = { Text(label) }, isError = if (index == 1) quantityText.toIntOrNull()?.let { it !in 1..999 } ?: true else index == 0 && draft.name.isBlank(),
                            supportingText = { if (index == 1) Text("Enter a quantity from 1 to 999.") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    }
                    SwitchRow("Mandatory", draft.mandatory) { draft = draft.copy(mandatory = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = validDraft, onClick = { onEdit(draft.copy(verified = if (draft != requirement) false else draft.verified,
                            commitment = if (draft.specification != requirement.specification && draft.commitment == Commitment.Confirmed) Commitment.Proposed else draft.commitment)); editing = false }, shape = RoundedCornerShape(18.dp)) { Text("Save") }
                        TextButton(onClick = { draft = requirement; quantityText = requirement.quantity.toString(); editing = false }) { Text("Cancel") }
                    }
                    if (draft.specification != requirement.specification) Text("Changing the specification marks any existing commitment for review.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(requirement.summary, if (requirement.coverage == Coverage.Covered) Tone.Settled else Tone.Attention)
                        if (!requirement.mandatory) StatusPill("Negotiable", Tone.Neutral)
                    }
                    KeyValue("Quantity", requirement.quantity.toString())
                    KeyValue("Specification", requirement.specification)
                    KeyValue("Location", requirement.location)
                    KeyValue("Required from", "${show.day(requirement.from)} ${show.label(requirement.from)}")
                    KeyValue("Required until", "${show.day(requirement.until)} ${show.label(requirement.until)}")
                    if (requirement.substitutions.isNotEmpty()) KeyValue("Permitted substitutions", requirement.substitutions)
                    if (requirement.supply.isNotEmpty()) KeyValue("Current supply", requirement.supply)
                    if (requirement.riderRef.isNotEmpty()) KeyValue("Rider", requirement.riderRef)
                    if (requirement.notes.isNotEmpty()) Text(requirement.notes, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { editing = true }, shape = RoundedCornerShape(18.dp)) { Text("Edit") }
                        if (requirement.coverage == Coverage.Covered) TextButton(onClick = { onVerify(requirement.id) }) { Text(if (requirement.verified) "Undo check" else "Record check") }
                    }
                }
            }
        }
        item { Section("Dependencies") { } }
        val dependencies = show.requirements.filter { it.act == requirement.act && it.group == requirement.group && it.id != requirement.id }
        items(dependencies) { dependency ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(dependency.name, style = MaterialTheme.typography.bodyLarge)
                    StatusPill(dependency.summary, if (dependency.coverage == Coverage.Covered) Tone.Settled else Tone.Attention)
                }
            }
        }
    }
}

@Composable private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
