package dev.deal.embedding

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** One wall-clock deadline per transfer, so a dripping peer cannot hold a lane open. */
private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "embedding-image-deadline").apply { isDaemon = true }
}

/**
 * The one permitted resource transport: HTTPS or loopback HTTP under the shared rule, no redirect,
 * no credential and no request body, bounded by the configured byte bound and one wall-clock deadline.
 * Blocking reads run on the caller's background lane and are aborted through the request's stop.
 */
internal object HttpImageTransport : ImageTransport {
    override fun bytes(url: String, maximumBytes: Int, timeoutMs: Int, stop: ImageStop): ByteArray {
        val uri = TransportPolicy.endpoint(url)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        // A redirect would silently switch transport, so it fails instead.
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "image/*")
        val cancellation = stop.onStop { connection.disconnect() }
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline = deadlines.schedule({ expired.set(true); connection.disconnect() }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            if (stop.isStopped) throw ImageFailure("IMAGE_CANCELLED")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw ImageFailure("IMAGE_HTTP_$status")
            if (connection.contentLengthLong > maximumBytes) throw ImageFailure("IMAGE_LIMIT")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { source ->
                val chunk = ByteArray(8192)
                while (true) {
                    if (stop.isStopped) throw ImageFailure("IMAGE_CANCELLED")
                    if (expired.get()) throw ImageFailure("IMAGE_TIMEOUT")
                    val count = source.read(chunk)
                    if (count < 0) break
                    if (count > maximumBytes - output.size()) throw ImageFailure("IMAGE_LIMIT")
                    output.write(chunk, 0, count)
                }
            }
            return output.toByteArray()
        } catch (error: ImageFailure) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            throw ImageFailure("IMAGE_TIMEOUT")
        } catch (error: java.io.IOException) {
            throw ImageFailure(
                if (stop.isStopped) "IMAGE_CANCELLED" else if (expired.get()) "IMAGE_TIMEOUT" else "IMAGE_UNAVAILABLE"
            )
        } finally {
            deadline.cancel(false)
            cancellation.close()
            connection.disconnect()
        }
    }
}
