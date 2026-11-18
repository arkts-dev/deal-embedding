package dev.deal.apps.rental

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.deal.shell.*
import dev.deal.embedding.discovery.DiscoveryFact
import java.time.LocalDate
import java.util.UUID


@OptIn(ExperimentalMaterial3Api::class)
class RentalActivity : ComponentActivity() {
    private val store by lazy { RentalStore(this) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        var tab by mutableStateOf(0)
        var category by mutableStateOf("All")
        var basket by mutableStateOf<Map<String, Int>>(emptyMap())
        var period by mutableStateOf(defaultPeriod())
        var openItem by mutableStateOf<RentalItem?>(null)
        var openQuote by mutableStateOf<Quote?>(null)
        var openReservation by mutableStateOf<Reservation?>(null)
        var reservations by mutableStateOf(store.reservations())
        val from = period.first
        val until = period.second
        val availability = store.availability(from, until)
        fun commitBasket() {
            val quote = Quote(UUID.randomUUID().toString(), basket.map { QuoteLine(it.key, it.value) }, from, until, System.currentTimeMillis() + 15 * 60_000, "Exact items only")
            store.saveQuotes(store.quotes() + quote); openQuote = quote; basket = emptyMap()
        }
        setContent {
            AppTheme(Accent.Rental) {
                AuroraBackdrop(Modifier.fillMaxSize()) {
                    Scaffold(containerColor = Color.Transparent, topBar = {
                        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent), title = {
                            Column { Text("Equipment Rental", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("Demo reservation · no real supplier", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }, actions = {
                            AssistanceSettingsButton()
                            if (basket.isNotEmpty()) BadgedBox(badge = { Badge { Text(basket.values.sum().toString()) } }) { IconButton(onClick = { commitBasket() }) { Icon(Icons.Outlined.ShoppingCart, "Review basket") } }
                        })
                    }, bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.Storefront, null) }, label = { Text("Catalogue") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.EventAvailable, null) }, label = { Text("Reservations") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Outlined.Shield, null) }, label = { Text("Review") })
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            when {
                                tab == 2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    ProposalReview(this@RentalActivity, "rental") { proposal ->
                                        val lines = parseLines(proposal.getString("items")); require(lines.isNotEmpty() && lines.size <= 16)
                                        require(lines.map { it.first }.distinct().size == lines.size && lines.all { store.item(it.first) != null && it.second in 1..32 })
                                        val start = java.time.Instant.parse(proposal.getString("from")).toEpochMilli()
                                        val end = java.time.Instant.parse(proposal.getString("until")).toEpochMilli(); require(start < end)
                                        val stock = store.availability(start, end); require(lines.all { (stock[it.first] ?: 0) >= it.second })
                                        val id = UUID.randomUUID().toString()
                                        val quote = Quote(id, lines.map { QuoteLine(it.first, it.second) }, start, end, System.currentTimeMillis() + 900000, "Exact items only")
                                        store.saveQuotes(store.quotes() + quote)
                                        val reservation = Reservation(id, quote.id, "Shared demo proposal", quote.lines, start, end, proposal.getString("collector"))
                                        store.saveReservations(store.reservations() + reservation); reservations = store.reservations()
                                        org.json.JSONObject().put("state", "confirmed").put("reservationId", id).put("detail", "Demo reservation saved after native confirmation")
                                    }
                                }
                                tab == 1 -> ReservationList(reservations, onOpen = { openReservation = it })
                                openReservation != null -> ReservationDetail(openReservation!!, availability) { updated ->
                                    reservations = reservations.map { if (it.id == updated.id) updated else it }; store.saveReservations(reservations); openReservation = updated
                                }
                                openQuote != null -> QuoteScreen(openQuote!!, store, availability, onBack = { openQuote = null }, onConfirmed = { reservation ->
                                    reservations = reservations + reservation; store.saveReservations(reservations); openQuote = null; openReservation = reservation
                                })
                                openItem != null -> AssistedTask(listOf(DiscoveryFact("equipment", openItem!!.let { item ->
                                    "Synthetic demo rental item ${item.id}: ${item.name}. ${item.specification}. Stock ${item.quantity}. Hire price ${item.cents} EUR cents per item. Included: ${item.included.joinToString()}. Not included: ${item.notIncluded.joinToString()}. Selected period ${java.time.Instant.ofEpochMilli(from)} to ${java.time.Instant.ofEpochMilli(until)}."
                                }))) {
                                    ItemDetail(openItem!!, availability[openItem!!.id] ?: 0, inBasket = basket[openItem!!.id] ?: 0,
                                        onAdd = { basket = basket + (openItem!!.id to (basket[openItem!!.id] ?: 0) + 1) }, onBack = { openItem = null })
                                }
                                else -> Catalogue(store, category, { category = it }, availability, from, until, period = { period = it }, basket = basket,
                                    onOpen = { openItem = it }, onAdd = { id -> basket = basket + (id to (basket[id] ?: 0) + 1) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun defaultPeriod(): Pair<Long, Long> {
    val day = dev.deal.apps.rental.nextFriday()
    return day.atTime(14, 0).atZone(RENTAL_ZONE).toInstant().toEpochMilli() to day.plusDays(1).atTime(12, 0).atZone(RENTAL_ZONE).toInstant().toEpochMilli()
}

@Composable private fun Catalogue(store: RentalStore, category: String, onCategory: (String) -> Unit, availability: Map<String, Int>, from: Long, until: Long, period: ((Pair<Long, Long>) -> Unit)?, basket: Map<String, Int>, onOpen: (RentalItem) -> Unit, onAdd: (String) -> Unit) {
    val effective = if (from == 0L) defaultPeriod() else from to until
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Hero("FOR HIRE", "Stands, cables, backline", "Complete kits, honest specifications and no hidden substitutions.", actions = { AssistChip(onClick = { period?.invoke(defaultPeriod()) }, label = { Text("Friday 14:00 → Saturday 12:00") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) }) }, illustration = { Art.BoomStand(120.dp) })
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("All", "Microphones", "Cables", "Backline").forEach { Chip(it, category == it) { onCategory(it) } }
            }
        }
        items(store.catalogue.filter { category == "All" || it.category == category }) { item ->
            ItemCard(item, availability[item.id] ?: 0, basket[item.id] ?: 0, onOpen = { onOpen(item) }, onAdd = { onAdd(item.id) })
        }
    }
}

@Composable private fun ItemCard(item: RentalItem, available: Int, inBasket: Int, onOpen: () -> Unit, onAdd: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtFor(item.art, 78.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(item.specification, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("€%.2f".format(item.cents / 100.0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusPill(if (available > 0) "$available available" else "Unavailable", if (available > 0) Tone.Settled else Tone.Attention)
                    if (inBasket > 0) StatusPill("$inBasket in basket", Tone.Progress)
                }
            }
            FilledTonalIconButton(onClick = onAdd, enabled = available > inBasket) { Icon(Icons.Outlined.Add, "Add ${item.name}") }
        }
    }
}

@Composable private fun ArtFor(art: String, size: androidx.compose.ui.unit.Dp) = when (art) {
    "stand" -> Art.BoomStand(size); "cable" -> Art.Cable(size); "package" -> Art.BoomStand(size); "amp" -> Art.Amplifier(size); else -> Art.Speaker(size)
}

@Composable private fun ItemDetail(item: RentalItem, available: Int, inBasket: Int, onAdd: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ArtFor(item.art, 150.dp) }
            Text(item.specification, style = MaterialTheme.typography.bodyLarge)
            KeyValue("Category", item.category)
            KeyValue("Available for the period", available.toString())
            KeyValue("Rental", "€%.2f".format(item.cents / 100.0))
            KeyValue("Deposit", "€50.00 refundable")
            if (item.included.isNotEmpty()) {
                Text("Included", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                item.included.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (item.notIncluded.isNotEmpty()) {
                Text("Not included", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                item.notIncluded.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(onClick = onAdd, enabled = available > inBasket, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Add to quote") }
        }
    }
}

@Composable private fun QuoteScreen(quote: Quote, store: RentalStore, availability: Map<String, Int>, onBack: () -> Unit, onConfirmed: (Reservation) -> Unit) {
    val expired = System.currentTimeMillis() > quote.expiresAt
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Text("Quote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Panel {
            quote.lines.forEach { line ->
                val item = store.item(line.itemId)
                KeyValue("${line.quantity} × ${item?.name ?: line.itemId}", "€%.2f".format(store.lineTotal(line) / 100.0))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            KeyValue("Rental charges", "€%.2f".format(store.quoteTotal(quote) / 100.0))
            KeyValue("Deposit", "€50.00", emphasis = true)
            KeyValue("Collection", "Friday 14:00–18:00 · collection desk")
            KeyValue("Return", "Saturday 10:00–12:00")
            StatusPill(if (expired) "Expired · refresh to continue" else "Valid for 15 minutes", if (expired) Tone.Attention else Tone.Settled)
            if (availability.values.any { it == 0 }) Text("Some items are no longer available for this period.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Text("Exact items only. Substitutions require your approval.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val reservation = Reservation(UUID.randomUUID().toString(), quote.id, "Friday at The Foundry", quote.lines, quote.from, quote.until, "Alex")
                onConfirmed(reservation)
            }, enabled = !expired, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Confirm demo reservation") }
        }
    }
}

@Composable private fun ReservationList(reservations: List<Reservation>, onOpen: (Reservation) -> Unit) {
    val upcoming = reservations.filter { !it.cancelled && it.collected.isEmpty() }
    val active = reservations.filter { it.collected.isNotEmpty() && !it.returned && !it.cancelled }
    val closed = reservations.filter { it.returned || it.cancelled }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Hero("RESERVATIONS", "Booked and collected", "Item-level state, so a partial collection stays visible.", illustration = { Art.Cable(110.dp) }) }
        if (reservations.isEmpty()) item { Panel { Text("No reservations yet.", style = MaterialTheme.typography.titleMedium); Text("Build a quote from the catalogue.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        listOf("Upcoming" to upcoming, "Active" to active, "Closed" to closed).forEach { (label, list) ->
            if (list.isNotEmpty()) {
                item { Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(list) { reservation ->
                    Card(onClick = { onOpen(reservation) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(reservation.show, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(reservation.lines.joinToString(", ") { line -> "${line.quantity} × ${line.itemId}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusPill(if (reservation.cancelled) "Cancelled" else if (reservation.returned) "Returned" else if (reservation.collected.isNotEmpty()) "Collected" else "Reserved",
                                    if (reservation.cancelled) Tone.Attention else if (reservation.returned) Tone.Neutral else Tone.Progress)
                                StatusPill(reservation.collector, Tone.Neutral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ReservationDetail(reservation: Reservation, availability: Map<String, Int>, onChange: (Reservation) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(reservation.show, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Reservation ${reservation.id.take(8)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Panel {
            reservation.lines.forEach { line ->
                val itemName = line.itemId
                val collected = line.itemId in reservation.collected
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("${line.quantity} × $itemName"); if (!collected && reservation.collected.isNotEmpty()) Text("outstanding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    StatusPill(if (collected) "Collected" else "To collect", if (collected) Tone.Settled else Tone.Progress)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            KeyValue("Collector", reservation.collector)
            KeyValue("Deposit", "€%.2f".format(reservation.depositCents / 100.0))
            if (!reservation.cancelled) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onChange(reservation.copy(collected = reservation.lines.map { it.itemId })) }, shape = RoundedCornerShape(18.dp), enabled = reservation.collected.isEmpty()) { Text("Mark collected") }
                    OutlinedButton(onClick = { onChange(reservation.copy(returned = true)) }, shape = RoundedCornerShape(18.dp), enabled = reservation.collected.isNotEmpty() && !reservation.returned) { Text("Mark returned") }
                    TextButton(onClick = { onChange(reservation.copy(cancelled = true)) }) { Text("Cancel reservation") }
                }
            } else Text("Cancelled. Reserved stock has been released.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cancelling here does not delete tasks or calendar activities in other apps.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun nextFriday(today: LocalDate = LocalDate.now()): LocalDate {
    var candidate = today.plusDays(1)
    while (candidate.dayOfWeek.value != 5) candidate = candidate.plusDays(1)
    return candidate
}
