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
    val connectTimeoutMs: Int = 5000,
    val readTimeoutMs: Int = 420_000
) {
    init {
        val uri = URI(endpoint)
        require(uri.isAbsolute && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid generation gateway endpoint" }
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")) { "Generation gateway requires HTTPS or loopback HTTP" }
        require(backend.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid generation backend" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Gateway timeouts must be positive" }
    }
}

/** Implements core/host/model.complete for the existing compiled generate path. No retries or redirects. */
internal class GatewayModelClient(private val config: GenerationGatewayConfig) : ModelClient {
    override fun complete(input: String, previous: String, diagnostics: String, cancellation: GenerationCancellation, log: EmbeddingLog): String {
        cancellation.check()
        val capture = log.capturesContent
        val body = JSONObject().put("input", input).put("previous", previous).put("diagnostics", diagnostics).put("capture", capture).toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 256 * 1024) { "Gateway request too large" }
        val connection = URI(config.endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Deal-Generation-Backend", config.backend)
        // Fixed-length streaming writes the entire request before awaiting a reply.
        connection.setFixedLengthStreamingMode(body.size)
        val registration = cancellation.onCancel { connection.disconnect() }
        try {
            cancellation.check()
            connection.outputStream.use { it.write(body) }
            cancellation.check()
            val status = connection.responseCode
            log.event("transport", "response", target = "generation gateway", code = "HTTP_$status")
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val payload = stream?.use { source ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    cancellation.check()
                    val count = source.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw ProtocolFailure("GATEWAY_FAILURE", "Gateway response too large")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            cancellation.check()
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
            if (content.toByteArray(Charsets.UTF_8).size > 256 * 1024) throw ProtocolFailure("GATEWAY_FAILURE", "Model content too large")
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
            return content
        } catch (error: Exception) {
            cancellation.check()
            when (error) {
                is ProtocolFailure -> throw error
                is SocketTimeoutException -> throw ProtocolFailure("MODEL_TIMEOUT", "Generation gateway timed out")
                is java.io.IOException -> throw ProtocolFailure("PROVIDER_UNREACHABLE", "Generation gateway unavailable")
                else -> throw error
            }
        } finally {
            registration.close()
            connection.disconnect()
        }
    }
}
