package dev.deal.embedding

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deterministic items for the one resource transport: a loopback host under the shared rule serves,
 * refuses, redirects, exceeds the bound, drips and is cut off mid-transfer. Every item drives the
 * production [HttpImageTransport], so a dropped redirect, bound or stop check fails here.
 */

/** A loopback host whose response per path the item chooses. */
private class Host : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker = Thread { accept() }.apply { isDaemon = true; start() }
    val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val port get() = socket.localPort
    val origin get() = "http://127.0.0.1:$port"

    private fun accept() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            Thread { serve(client) }.apply { isDaemon = true; start() }
        }
    }

    private fun serve(client: java.net.Socket) {
        runCatching {
            client.use { connection ->
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.US_ASCII))
                val path = (reader.readLine() ?: return).split(" ").getOrNull(1) ?: return
                requested.add(path)
                while (true) { if ((reader.readLine() ?: break).isEmpty()) break }
                val output = connection.getOutputStream()
                when (path) {
                    "/picture" -> reply(output, 200, BEACON)
                    "/moved" -> {
                        output.write("HTTP/1.1 302 Found\r\nLocation: https://example.test/picture\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                    "/large" -> reply(output, 200, ByteArray(4096))
                    // The header claims ten bytes and the peer then sends 4096, so only the streaming
                    // bound can stop it.
                    "/oversize" -> {
                        head(output, 200, 10)
                        output.write(ByteArray(4096))
                        output.flush()
                    }
                    "/drip" -> {
                        head(output, 200, BEACON.size * 2)
                        output.write(BEACON.copyOfRange(0, 1)); output.flush()
                        // A peer that keeps one byte alive past the deadline must not hold the lane.
                        Thread.sleep(3000)
                        runCatching { output.write(BEACON.copyOfRange(1, BEACON.size)); output.flush() }
                    }
                    "/endless" -> {
                        head(output, 200, BEACON.size * 2)
                        var written = 0
                        val end = System.nanoTime() + 5_000_000_000L
                        while (written < BEACON.size && System.nanoTime() < end) {
                            runCatching { output.write(BEACON.copyOfRange(written, written + 1)); output.flush() }.
                                onFailure { return }
                            written++
                            Thread.sleep(50)
                        }
                    }
                    else -> reply(output, 404, "missing".toByteArray())
                }
            }
        }.onFailure { }
    }

    private fun reply(output: OutputStream, status: Int, body: ByteArray) {
        head(output, status, body.size)
        output.write(body)
        output.flush()
    }

    private fun head(output: OutputStream, status: Int, length: Int) {
        val reason = if (status == 200) "OK" else "Status $status"
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: image/png\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
        worker.interrupt()
    }

    companion object {
        val BEACON = ByteArray(64) { (it + 1).toByte() }
    }
}

private fun refusal(block: () -> Unit): String {
    val failure = runCatching(block).exceptionOrNull()
    check(failure is ImageFailure) { "expected a classified image failure, got $failure" }
    return failure.code
}

/** Runs the production transport on its own thread, since its reads block. */
private fun transfer(host: String, stop: ImageStop = ImageStop(), maximumBytes: Int = 1024, timeoutMs: Int = 1000): ByteArray =
    HttpImageTransport.bytes(host, maximumBytes, timeoutMs, stop)

fun main() {
    // The permitted endpoint, served: the production transport returns exactly the resource bytes.
    Host().use { host ->
        check(transfer(host.origin + "/picture").contentEquals(Host.BEACON)) { "the loopback picture bytes must be returned" }
        check(host.requested == listOf("/picture")) { "one transfer issues one request, got ${host.requested}" }
        // The scheme and host are case-insensitive, so a permitted endpoint still transfers.
        check(transfer("HTTP://127.0.0.1:${host.port}/picture").contentEquals(Host.BEACON)) {
            "an uppercase scheme must not fail a permitted endpoint"
        }
        check(refusal { transfer(host.origin + "/missing") } == "IMAGE_HTTP_404") { "a missing resource is classified" }
        check(refusal { transfer(host.origin + "/moved") } == "IMAGE_HTTP_302") { "a redirect is refused, never followed" }
        check(refusal { transfer(host.origin + "/large", maximumBytes = 1024) } == "IMAGE_LIMIT") {
            "a transfer beyond the byte bound fails rather than truncating"
        }
        check(refusal { transfer(host.origin + "/oversize", maximumBytes = 1024) } == "IMAGE_LIMIT") {
            "a peer whose length header lies is still bounded while streaming"
        }
        // A drip-fed peer is cut off at the one wall-clock deadline instead of holding the lane.
        val startedAt = System.nanoTime()
        check(refusal { transfer(host.origin + "/drip", timeoutMs = 300) } == "IMAGE_TIMEOUT") { "a drip-fed peer times out" }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        check(elapsedMs < 2500) { "the deadline bounds the whole transfer, took ${elapsedMs}ms" }
    }

    // An impermissible endpoint is refused by the shared rule before any connection is attempted.
    for (endpoint in listOf("http://example.test/picture", "HTTP://EXAMPLE.TEST/picture", "file:///etc/hosts",
                            "https://user:secret@example.test/picture.png")) {
        val failure = runCatching { HttpImageTransport.bytes(endpoint, 1024, 1000, ImageStop()) }.exceptionOrNull()
        check(failure is IllegalArgumentException) { "$endpoint must be refused, got $failure" }
        check(failure.message.orEmpty().lowercase().contains("transport")) { "$endpoint must name the transport rule, got $failure" }
    }

    // A transfer stopped while a peer is still writing is cancelled, not completed.
    Host().use { host ->
        val stop = ImageStop()
        val outcome = java.util.concurrent.atomic.AtomicReference<String>()
        val finished = CountDownLatch(1)
        Thread {
            outcome.set(runCatching { HttpImageTransport.bytes(host.origin + "/endless", 4096, 10_000, stop) }
                .exceptionOrNull()?.let { (it as? ImageFailure)?.code ?: it.toString() })
            finished.countDown()
        }.apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (host.requested.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        check(host.requested.isNotEmpty()) { "the peer was never asked for the resource" }
        Thread.sleep(150)
        stop.stop()
        check(finished.await(5, TimeUnit.SECONDS)) { "a stopped transfer must end" }
        check(outcome.get() == "IMAGE_CANCELLED") { "a stopped transfer is classified cancelled, got ${outcome.get()}" }
    }

    println("image transport: served bytes, refusal, redirect, byte bound, deadline, scheme and cancellation items passed")
}
