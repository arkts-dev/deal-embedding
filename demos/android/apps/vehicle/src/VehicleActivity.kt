package dev.deal.apps.vehicle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deal.embedding.discovery.DiscoveryFact
import dev.deal.shell.*

@OptIn(ExperimentalMaterial3Api::class)
class VehicleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme(Accent.Rental) {
            var distance by remember { mutableFloatStateOf(12f) }
            Scaffold(topBar = { TopAppBar(title = { Text("Vehicle") }, actions = { AssistanceSettingsButton() }) }) { padding ->
                Box(Modifier.padding(padding)) {
                    AssistedTask(listOf(DiscoveryFact("trip", "Synthetic demo trip: collect equipment from the rental desk for the Static Bloom show at The Foundry, 12 Foundry Lane, Berlin. Distance ${distance.toInt()} km. Vehicle estimate ${5 + 2 * distance.toInt()} minutes using a simulation, not traffic or route telemetry. The show date and deadlines must be read from shared records; no journey or collection has happened."))) {
                        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Equipment collection", style = MaterialTheme.typography.headlineSmall)
                            Text("The Foundry · demo trip", style = MaterialTheme.typography.bodyMedium)
                            Text("${5 + 2 * distance.toInt()} min", style = MaterialTheme.typography.displayMedium)
                            Text("${distance.toInt()} km", style = MaterialTheme.typography.titleMedium)
                            Slider(distance, { distance = it }, valueRange = 1f..60f, steps = 58)
                            Text("Simulated estimate, not live traffic or a connected vehicle.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } }
    }
}
