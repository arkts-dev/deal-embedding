package dev.deal.embedding

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException

/** Native bootstrap configuration, not an experience-visible capability or provider credential. */
class GenerationGatewayConfig(
    val endpoint: String,
    val backend: String,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 180_000,
    val deadlineMs: Int = 240_000,
    val requestBytes: Int = 16 * 1024 * 1024,
    val responseBytes: Int = 16 * 1024 * 1024
) {
    init {
        val uri = URI(endpoint)
        require(uri.isAbsolute && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid generation gateway endpoint" }
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")) { "Generation gateway requires HTTPS or loopback HTTP" }
        require(backend.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid generation backend" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && deadlineMs > 0 && requestBytes > 0 && responseBytes > 0) { "Gateway limits must be positive" }
    }
}

/** Single bounded inference transport. No stage inference, retries or redirects. */
internal class GatewayModelClient(private val config: GenerationGatewayConfig) : ModelClient {
    companion object {
        private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "embedding-inference-deadline").apply { isDaemon = true }
        }
    }
    override fun complete(request: InferenceRequest, cancellation: GenerationCancellation, log: EmbeddingLog): String = dispatch(request,cancellation,log).content
    override fun dispatch(request: InferenceRequest, cancellation: GenerationCancellation, log: EmbeddingLog): InferenceResponse {
        cancellation.check()
        require(request.ask > 0 && request.maxTokens > 0)
        val capture = log.capturesContent
        val body = JSONObject().put("input", request.input).put("previous", request.previous).put("diagnostics", request.diagnostics)
            .put("operation",request.operation).put("stage",request.stage).put("ask",request.ask).put("maxTokens",request.maxTokens)
            .put("thinking",request.thinking).put("questions",request.questions).put("capture", capture).toString().toByteArray(Charsets.UTF_8)
        if (body.size > config.requestBytes) throw ProtocolFailure("GATEWAY_REQUEST_LIMIT", "Generation request exceeds configured byte limit")
        val connection = URI(config.endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = minOf(config.connectTimeoutMs, config.deadlineMs)
        connection.readTimeout = minOf(config.readTimeoutMs, config.deadlineMs)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Deal-Generation-Backend", config.backend)
        // Fixed-length streaming writes the entire request before awaiting a reply.
        connection.setFixedLengthStreamingMode(body.size)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = System.nanoTime()
        val deadline = deadlines.schedule({ expired.set(true); connection.disconnect() }, config.deadlineMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        val registration = cancellation.onCancel { connection.disconnect() }
        fun checkDeadline() {
            cancellation.check()
            if (expired.get() || (System.nanoTime() - started) / 1_000_000 >= config.deadlineMs) throw ProtocolFailure("MODEL_TIMEOUT", "Generation gateway deadline exceeded")
        }
        try {
            cancellation.check()
            connection.outputStream.use { it.write(body) }
            cancellation.check()
            val status = connection.responseCode
            log.event("transport", "response", target = "generation gateway", code = "HTTP_$status")
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val payload = stream?.use { source ->
                if (connection.contentLengthLong > config.responseBytes) throw ProtocolFailure("GATEWAY_RESPONSE_LIMIT", "Generation response exceeds configured byte limit")
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    checkDeadline()
                    val count = source.read(chunk)
                    if (count < 0) break
                    if (count > config.responseBytes - output.size()) throw ProtocolFailure("GATEWAY_RESPONSE_LIMIT", "Generation response exceeds configured byte limit")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            checkDeadline()
            if (status != 200) {
                val error = runCatching { JSONObject(payload.toString(Charsets.UTF_8)).getJSONObject("error") }.getOrNull()
                val code = error?.optString("code")?.takeIf { it in setOf("AUDIT_LIMIT", "BACKEND_UNAVAILABLE", "PROVIDER_HTTP", "MODEL_TIMEOUT", "PROVIDER_UNREACHABLE", "GATEWAY_FAILURE") } ?: "HTTP_$status"
                log.event("model-provider", "failed", code = code, target = "generation gateway")
                // Do not reflect arbitrary server bodies, URLs or credentials into diagnostics.
                throw ProtocolFailure(code, "Generation gateway HTTP $status · $code" + (error?.optInt("providerStatus", 0)?.takeIf { it in 100..599 }?.let { " · provider HTTP $it" } ?: ""))
            }
            val envelope = try { JSONObject(payload.toString(Charsets.UTF_8)) } catch (error: Exception) {
                throw ProtocolFailure("GATEWAY_FAILURE", "Invalid generation gateway response")
            }
            val content = envelope.opt("content") as? String ?: throw ProtocolFailure("GATEWAY_FAILURE", "Missing model content")
            val model = envelope.opt("model") as? String ?: throw ProtocolFailure("GATEWAY_FAILURE", "Missing model provenance")
            val metadata = envelope.optJSONObject("metadata") ?: throw ProtocolFailure("GATEWAY_FAILURE", "Missing model metadata")
            log.event("model-provider", "completed", target = model + " " + metadata.toString())
            if (envelope.optBoolean("captureOmitted")) log.event("model-provider", "omitted", code = "CAPTURE_LIMIT", target = model)
            else if (capture) {
                // Evidence omissions never replace a valid model result.
                for (field in listOf("request", "response")) {
                    val detail = envelope.optJSONObject(field)
                    if (detail != null) log.event("model-provider", field, target = model, detail = detail.toString())
                    else log.event("model-provider", "omitted", code = "CAPTURE_MISSING", target = model)
                }
            }
            return InferenceResponse(content,envelope.optString("finish","unknown"),envelope.optString("refusal",""))
        } catch (error: Exception) {
            checkDeadline()
            when (error) {
                is ProtocolFailure -> throw error
                is SocketTimeoutException -> throw ProtocolFailure("MODEL_TIMEOUT", "Generation gateway timed out")
                is java.io.IOException -> throw ProtocolFailure("PROVIDER_UNREACHABLE", "Generation gateway unavailable")
                else -> throw error
            }
        } finally {
            deadline.cancel(false)
            registration.close()
            connection.disconnect()
        }
    }
}
