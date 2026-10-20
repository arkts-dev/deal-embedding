package dev.deal.embedding.discovery

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.*
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import java.util.concurrent.*

/** ML Kit/AICore is confined to this implementation; unavailable inference is an error, not abstention. */
internal class NanoDiscovery(private val context: Context) : DiscoveryInference {
    private val executor = Executors.newFixedThreadPool(4)
    private var model: GenerativeModelFutures? = null
    private var active: Future<*>? = null
    private var stopped = false
    private var epoch = 0L
    private fun client(): GenerativeModelFutures = synchronized(this) {
        check(!stopped)
        model ?: run {
            MlKitContext.initializeIfNeeded(context, listOf(CommonComponentRegistrar()))
            GenerativeModelFutures.from(Generation.getClient(GenerationConfig.Builder().apply { workerExecutor = executor }.build())).also { model = it }
        }
    }
    private fun <T> await(epoch: Long, future: Future<T>, seconds: Long): T {
        synchronized(this) {
            if (stopped || this.epoch != epoch) { future.cancel(true); throw CancellationException("Discovery cancelled") }
            active = future
        }
        try { return future.get(seconds, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
        catch (error: TimeoutException) { future.cancel(true); throw error }
        finally { synchronized(this) { if (active === future) active = null } }
    }
    override fun complete(prompt: String, log: dev.deal.embedding.EmbeddingLog): String {
        val version = synchronized(this) { check(!stopped); epoch }
        val client = client()
        check(await(version, client.checkStatus(), 30) == FeatureStatus.AVAILABLE) { "On-device discovery model unavailable" }
        val request = GenerateContentRequest.Builder(TextPart(prompt)).apply {
            maxOutputTokens = 4096; temperature = 0.0f; enableThinking = true
        }.build()
        val response = await(version, client.generateContent(request), 90)
        check(response.candidates.size == 1) { "Missing discovery candidate" }
        val candidate = response.candidates.single()
        log.event("opportunity-inference", "metadata", target = "On-device",
            operation = "maxOutputTokens=4096;thinking=true;finishReason=${candidate.finishReason}")
        return candidate.text
    }
    @Synchronized override fun cancel() { epoch++; active?.cancel(true); active = null }
    override fun close() {
        synchronized(this) { if (stopped) return; stopped = true; cancel() }
        dev.deal.embedding.teardown({ model?.getGenerativeModel()?.close() }, { executor.shutdownNow() }, { check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Nano executor did not terminate" } })
    }
}
