package dev.deal.cutover.tests

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.deal.embedding.DealRenderer
import dev.deal.embedding.EmbeddedExperience
import org.json.JSONObject

/**
 * Hosts one controlled Tabs tree for the device renderer check; the check replaces the tree per case.
 * The tree renders through the public painter exactly as a hosted surface renders it, and every
 * dispatch the painter delivers is kept, so the check observes the controls of the selected panel.
 */
class TabPreviewActivity : ComponentActivity() {
    companion object {
        /** The committed tree the production renderer draws, or null for an empty experience. */
        var tree: JSONObject? by mutableStateOf(null)

        /** Each dispatch the painter delivered: its action slot and payload spelling. */
        val delivered: MutableList<Pair<Int, String?>> = java.util.Collections.synchronizedList(mutableListOf())
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme {
                EmbeddedExperience {
                    tree?.let { DealRenderer(it, "screen") { slot, payload -> delivered.add(slot to payload) } }
                }
            }
        }
    }
}
