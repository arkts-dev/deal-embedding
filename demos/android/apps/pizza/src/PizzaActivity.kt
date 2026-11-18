package dev.deal.apps.pizza

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

@OptIn(ExperimentalMaterial3Api::class)
class PizzaActivity : ComponentActivity() {
    private val store by lazy { PizzaStore(this) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        var tab by mutableStateOf(0)
        var filter by mutableStateOf("All")
        var basket by mutableStateOf<Map<String, Int>>(emptyMap())
        var windowId by mutableStateOf("w1")
        var orders by mutableStateOf(store.orders())
        var openOrder by mutableStateOf<Order?>(null)
        var openItem by mutableStateOf<MenuItem?>(null)
        var confirming by mutableStateOf(false)
        setContent {
            AppTheme(Accent.Pizza) {
                AuroraBackdrop(Modifier.fillMaxSize()) {
                    Scaffold(containerColor = Color.Transparent, topBar = {
                        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent), title = {
                            Column { Text("Pizza", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("Demo order · no real purchase or delivery", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }, actions = {
                            AssistanceSettingsButton()
                            if (basket.isNotEmpty()) BadgedBox(badge = { Badge { Text(basket.values.sum().toString()) } }) { IconButton(onClick = { confirming = true }) { Icon(Icons.Outlined.ShoppingBag, "Review order") } }
                        })
                    }, bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.LocalPizza, null) }, label = { Text("Menu") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.Receipt, null) }, label = { Text("Orders") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Outlined.Shield, null) }, label = { Text("Review") })
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            when {
                                tab == 2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    ProposalReview(this@PizzaActivity, "pizza") { proposal ->
                                        val entries = proposal.getString("items").split(",").map { entry ->
                                            val parts = entry.trim().split(":"); require(parts.size == 2)
                                            parts[0].trim() to parts[1].trim().toInt()
                                        }
                                        require(entries.isNotEmpty() && entries.size <= 16 && entries.map { it.first }.distinct().size == entries.size)
                                        require(entries.all { store.item(it.first) != null && it.second in 1..32 })
                                        val window = proposal.getString("windowId"); require(store.window(window) != null)
                                        val lines = entries.toMap(); val id = store.newOrderId()
                                        val order = Order(id, "Shared demo proposal", lines, window, store.address, store.instructions, proposal.getString("recipient"), store.foodTotal(lines), store.deliveryCents)
                                        store.saveOrders(store.orders() + order); orders = store.orders()
                                        org.json.JSONObject().put("state", "confirmed").put("orderId", id).put("detail", "Demo order saved after native confirmation; no real purchase")
                                    }
                                }
                                tab == 1 -> Orders(orders, onOpen = { openOrder = it })
                                openOrder != null -> OrderDetail(openOrder!!, store) { updated ->
                                    orders = orders.map { if (it.id == updated.id) updated else it }; store.saveOrders(orders); openOrder = updated
                                }
                                confirming -> Basket(store, basket, windowId, { windowId = it }, onEdit = { confirming = false }, onOrder = { order ->
                                    orders = orders + order; store.saveOrders(orders); basket = emptyMap(); confirming = false; openOrder = order
                                })
                                openItem != null -> AssistedTask(listOf(DiscoveryFact("menu_item", openItem!!.let { item ->
                                    "Synthetic demo menu item ${item.id}: ${item.name}, ${item.size}, ${item.cents} EUR cents. Vegan ${item.vegan}; vegetarian ${item.vegetarian}. Ingredients ${item.ingredients}; allergens ${item.allergens}. Delivery charge ${store.deliveryCents} EUR cents. Delivery windows ${store.windows.joinToString { it.id + " " + it.from + "–" + it.until }}. Event delivery to ${store.address}; ${store.instructions}. Recipient ${store.recipient}. No purchase or delivery has occurred."
                                }))) {
                                    ProductDetail(openItem!!, onAdd = { basket = basket + (openItem!!.id to (basket[openItem!!.id] ?: 0) + 1) }, onBack = { openItem = null })
                                }
                                else -> Menu(store, filter, { filter = it }, basket) { openItem = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Menu(store: PizzaStore, filter: String, onFilter: (String) -> Unit, basket: Map<String, Int>, onOpen: (MenuItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Hero("DRESSING-ROOM DELIVERY", "Six pizzas, two vegan", "Delivered to Static Bloom before the half-hour. Mention allergies and we will be honest about them.", actions = { AssistChip(onClick = { }, label = { Text("The Foundry · dressing room") }, leadingIcon = { Icon(Icons.Outlined.Place, null) }) }, illustration = { Art.Pizza(130.dp) })
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("All", "Vegan", "Vegetarian").forEach { Chip(it, filter == it) { onFilter(it) } }
            }
        }
        items(store.menu.filter { filter == "All" || (filter == "Vegan" && it.vegan) || (filter == "Vegetarian" && it.vegetarian) }) { item ->
            Card(onClick = { onOpen(item) }, shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art.Pizza(76.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${item.size} · ${item.ingredients}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("€%.2f".format(item.cents / 100.0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (item.vegan) StatusPill("Vegan", Tone.Settled)
                            if (!item.vegan && item.vegetarian) StatusPill("Vegetarian", Tone.Neutral)
                            Text(item.allergens, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val count = basket[item.id] ?: 0
                        if (count > 0) StatusPill("$count in basket", Tone.Progress)
                    }
                }
            }
        }
        item {
            Panel {
                Text("Dietary information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Labels describe our menu data. They are not a guarantee about undeclared allergens or cross-contact in the kitchen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ProductDetail(item: MenuItem, onAdd: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Art.Pizza(170.dp) }
            KeyValue("Size", item.size)
            KeyValue("Ingredients", item.ingredients)
            KeyValue("Allergens", item.allergens)
            KeyValue("Price", "€%.2f".format(item.cents / 100.0), emphasis = true)
            if (item.vegan) StatusPill("Vegan", Tone.Settled) else if (item.vegetarian) StatusPill("Vegetarian", Tone.Neutral)
            Text("Fixed recipe. Removing an ingredient does not make a product verified vegan.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Add to basket") }
        }
    }
}

@Composable private fun Basket(store: PizzaStore, basket: Map<String, Int>, windowId: String, onWindow: (String) -> Unit, onEdit: () -> Unit, onOrder: (Order) -> Unit) {
    val food = store.foodTotal(basket)
    val total = food + store.deliveryCents
    val count = store.totalCount(basket)
    val vegan = store.veganCount(basket)
    val meetsDeadline = store.window(windowId)?.guaranteed == true
    val required = count >= 6 && vegan >= 2
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Order", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Panel {
            basket.forEach { (id, quantity) ->
                val item = store.item(id)
                KeyValue("$quantity × ${item?.name ?: id}", "€%.2f".format(((item?.cents ?: 0) * quantity) / 100.0))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            KeyValue("Pizzas", count.toString())
            KeyValue("Vegan pizzas", vegan.toString())
            KeyValue("Food", "€%.2f".format(food / 100.0))
            KeyValue("Delivery", "€%.2f".format(store.deliveryCents / 100.0))
            KeyValue("Total", "€%.2f".format(total / 100.0), emphasis = true)
        }
        Section("Requirement") { }
        Panel {
            KeyValue("Required", "6 pizzas, at least 2 vegan")
            KeyValue("Selected", "$count pizzas, $vegan vegan")
            KeyValue("Required by", "18:30")
            KeyValue("Delivery window", store.window(windowId)?.let { "${it.from}–${it.until}" } ?: "—")
            if (!required) StatusPill("Does not cover the requirement yet", Tone.Attention)
            if (!meetsDeadline) StatusPill("Cannot guarantee arrival by 18:30", Tone.Attention)
        }
        Section("Delivery window") { }
        store.windows.forEach { window ->
            Card(onClick = { onWindow(window.id) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (window.id == windowId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${window.from}–${window.until}", style = MaterialTheme.typography.titleMedium)
                    StatusPill(if (window.guaranteed) "Meets 18:30" else "Too late to guarantee", if (window.guaranteed) Tone.Settled else Tone.Attention)
                }
            }
        }
        Panel {
            KeyValue("Address", store.address)
            KeyValue("Recipient", store.recipient)
            KeyValue("Instructions", store.instructions)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(18.dp)) { Text("Edit products") }
            Button(onClick = {
                onOrder(Order(store.newOrderId(), "Friday at The Foundry", basket, windowId, store.address, store.instructions, store.recipient, food, store.deliveryCents))
            }, enabled = basket.isNotEmpty(), shape = RoundedCornerShape(18.dp)) { Text("Confirm demo order") }
        }
    }
}

@Composable private fun Orders(orders: List<Order>, onOpen: (Order) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Hero("ORDERS", "Demo orders", "Status changes are simulated here, never presented as live courier tracking.", illustration = { Art.Pizza(110.dp) }) }
        if (orders.isEmpty()) item { Panel { Text("No orders yet.", style = MaterialTheme.typography.titleMedium); Text("Choose pizzas from the menu.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(orders.sortedByDescending { it.status }) { order ->
            Card(onClick = { onOpen(order) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(order.show, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(order.lines.entries.joinToString(", ") { "${it.value} × ${it.key}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill(order.status, if (order.status == "Cancelled") Tone.Attention else Tone.Progress)
                        StatusPill("€%.2f".format(order.totalCents / 100.0), Tone.Neutral)
                    }
                }
            }
        }
    }
}

@Composable private fun OrderDetail(order: Order, store: PizzaStore, onChange: (Order) -> Unit) {
    val stages = listOf("Confirmed", "Preparing", "Out for delivery", "Delivered")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(order.show, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Order ${order.id.take(8)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Panel {
            order.lines.forEach { (id, count) -> KeyValue("$count × ${store.item(id)?.name ?: id}", "€%.2f".format(((store.item(id)?.cents ?: 0) * count) / 100.0)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            KeyValue("Delivery", "€%.2f".format(order.deliveryCents / 100.0))
            KeyValue("Total", "€%.2f".format(order.totalCents / 100.0), emphasis = true)
            KeyValue("Window", store.window(order.windowId)?.let { "${it.from}–${it.until}" } ?: "—")
            KeyValue("Destination", order.address)
            KeyValue("Recipient", order.recipient)
        }
        Panel {
            Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(order.status, style = MaterialTheme.typography.bodyLarge)
            Text("Advance the demo status explicitly. This is not live tracking.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val next = stages.getOrNull(stages.indexOf(order.status) + 1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { next?.let { onChange(order.copy(status = it)) } }, enabled = next != null, shape = RoundedCornerShape(18.dp)) { Text("Advance delivery status") }
                TextButton(onClick = { onChange(order.copy(status = "Cancelled")) }, enabled = order.status == "Confirmed") { Text("Cancel order") }
            }
            if (order.status != "Confirmed") Text("Cancellation is only possible while the order is confirmed.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
