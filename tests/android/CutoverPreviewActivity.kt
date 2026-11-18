package dev.deal.cutover.tests

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deal.embedding.*
import java.io.File
import java.util.concurrent.Executors

/** Visual replay of exact fresh probe output through the production renderer. */
class CutoverPreviewActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var api:ApplicationProtocol?=null
    private var surface:ExperienceSurface?=null
    private var experience:Experience?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val name=intent.getStringExtra("scenario")?:"budget"
        require(name.matches(Regex("[a-z]+")))
        worker.execute {
            try {
                val protocol=Embedding.open(this,EmbeddingConfig("v08-visual-"+java.util.UUID.randomUUID(),GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture")))
                api=protocol
                val directory=File(filesDir,"scenario-results")
                val candidate=protocol.load("Fresh $name",Source(File(directory,"$name.deal").readText(),File(directory,"$name.dealui").readText()))
                experience=candidate
                val owner=ExperienceSurface(protocol,candidate);surface=owner
                owner.activate { error->File(directory,"$name-preview-failure.txt").writeText(android.util.Log.getStackTraceString(error)) }
                runOnUiThread { setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(16.dp)) { EmbeddedExperience { ExperienceSurface(owner) } } } } }
            } catch(error:Throwable) {
                File(filesDir,"scenario-results/$name-preview-failure.txt").writeText(android.util.Log.getStackTraceString(error))
            }
        }
    }
    override fun onDestroy() {
        worker.execute { try { surface?.close();experience?.let{api?.forget(it)} } finally { api?.close() } }
        worker.shutdown()
        super.onDestroy()
    }
}
