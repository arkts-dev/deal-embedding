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
 * Hosts one controlled tree for the device renderer check; the check replaces the tree per case.
 * The tree renders through the public painter exactly as a hosted surface renders it.
 */
class ImagePreviewActivity : ComponentActivity() {
    companion object {
        /** The committed tree the production renderer draws, or null for an empty experience. */
        var tree: JSONObject? by mutableStateOf(null)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme {
                EmbeddedExperience {
                    tree?.let { DealRenderer(it, "screen") { _, _ -> } }
                }
            }
        }
    }
}
