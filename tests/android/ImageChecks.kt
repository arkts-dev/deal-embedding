package dev.deal.cutover.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device check of the actual painter over controlled loopback images: content display, explicit
 * failure with the alt text kept, source replacement and removal. The transfer is the production
 * HTTPS-or-loopback transport, so the server is an ordinary loopback HTTP host and the accessible
 * tree is read through the framework's accessibility bridge.
 */

private const val RED = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAb0lEQVR4nO3PAQkAAAyEwO9feoshgnABdLep8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3IPanc8OLDQitxAAAAAElFTkSuQmCC"
private const val GREEN = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAb0lEQVR4nO3PAQkAAAyEwO9feoshgnABdLu58QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ24Pbjs8OLsrFddAAAAAElFTkSuQmCC"
private const val BLUE = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAb0lEQVR4nO3PAQkAAAyEwO9feoshgnABdNvJ8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ2oPcf88OIhvJ6vAAAAAElFTkSuQmCC"

private val PIXELS = mapOf(
    "/red.png" to Base64.getDecoder().decode(RED),
    "/green.png" to Base64.getDecoder().decode(GREEN),
    "/blue.png" to Base64.getDecoder().decode(BLUE),
)

/** Loopback image host: a complete picture, a drip-fed picture and a missing resource. */
private class ImageHost : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val accepted = Thread { accept() }.apply { isDaemon = true; start() }
    val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** How each peer response ended; a write outcome is the peer's, not proof of what the client read. */
    val writes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val port get() = socket.localPort

    val origin get() = "http://127.0.0.1:$port"

    private fun accept() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            Thread { serve(client) }.apply { isDaemon = true; start() }
        }
    }

    private fun serve(client: java.net.Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.US_ASCII))
            val path = (reader.readLine() ?: return).split(" ").getOrNull(1) ?: return
            requested.add(path)
            while (true) { if ((reader.readLine() ?: break).isEmpty()) break }
            val output = connection.getOutputStream()
            val picture = PIXELS[path]
            when {
                path == "/slow.png" -> {
                    // The head and half of the picture reach the client, so a superseded request is stale.
                    head(output, GREEN_PIXELS.size)
                    output.write(GREEN_PIXELS.copyOfRange(0, 40))
                    output.flush()
                    Thread.sleep(1500)
                    val outcome = runCatching { output.write(GREEN_PIXELS.copyOfRange(40, GREEN_PIXELS.size)); output.flush() }
                    writes.add("/slow.png " + if (outcome.isSuccess) "written" else "write failed")
                }
                picture != null -> {
                    head(output, picture.size)
                    output.write(picture)
                    output.flush()
                    writes.add("$path written")
                }
                else -> {
                    val body = "missing".toByteArray()
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body)
                    output.flush()
                }
            }
        }
    }

    private fun head(output: OutputStream, length: Int) {
        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
        accepted.interrupt()
    }

    private val GREEN_PIXELS get() = PIXELS.getValue("/green.png")
}

private fun imageTree(src: String, alt: String): JSONObject = JSONObject()
    .put("component", "Column")
    .put("props", JSONArray())
    .put("children", JSONArray().put(
        JSONObject().put("component", "Image")
            .put("props", JSONArray()
                .put(JSONObject().put("name", "src").put("kind", "string").put("stringValue", src))
                .put(JSONObject().put("name", "alt").put("kind", "string").put("stringValue", alt)))
            .put("children", JSONArray())
    ))

private fun emptyTree(): JSONObject = JSONObject()
    .put("component", "Column").put("props", JSONArray()).put("children", JSONArray())

private fun frame(window: android.view.Window): Bitmap {
    val view = window.decorView
    check(view.width > 0 && view.height > 0) { "the rendered surface has no size" }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val copied = CountDownLatch(1)
    var outcome = -1
    PixelCopy.request(window, bitmap, { result -> outcome = result; copied.countDown() }, Handler(Looper.getMainLooper()))
    check(copied.await(8, TimeUnit.SECONDS)) { "no rendered frame was copied" }
    check(outcome == PixelCopy.SUCCESS) { "the rendered frame could not be copied: $outcome" }
    return bitmap
}

private fun shows(bitmap: Bitmap, red: Int, green: Int, blue: Int): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var matches = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        if (kotlin.math.abs(r - red) <= 24 && kotlin.math.abs(g - green) <= 24 && kotlin.math.abs(b - blue) <= 24) matches++
    }
    // A drawn picture covers a wide band; a few antialiased pixels are not content.
    return matches > 500
}

/**
 * The accessible strings of the rendered window, read through the instrumentation's accessibility
 * bridge, which seals the nodes the framework hands out.
 */
private fun descriptions(instrumentation: Instrumentation): List<String> {
    val automation = instrumentation.uiAutomation
    var root: AccessibilityNodeInfo? = null
    val deadline = android.os.SystemClock.elapsedRealtime() + 8000
    while (root == null && android.os.SystemClock.elapsedRealtime() < deadline) {
        root = automation.rootInActiveWindow
        if (root == null) Thread.sleep(50)
    }
    check(root != null) { "the accessibility bridge exposed no window" }
    val texts = mutableListOf<String>()
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 64) return
        node.text?.let { texts.add(it.toString()) }
        node.contentDescription?.let { texts.add(it.toString()) }
        for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
    }
    visit(root, 0)
    return texts
}

/** Runs the painter over controlled loopback pictures and reports the observed device evidence. */
fun checkImageRendering(instrumentation: Instrumentation, context: Context): String {
    val evidence = mutableListOf<String>()
    val host = ImageHost()
    var activity: Activity? = null
    try {
        activity = instrumentation.startActivitySync(Intent(context, ImagePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val live = activity

        fun show(tree: JSONObject) {
            live.runOnUiThread { ImagePreviewActivity.tree = tree }
            instrumentation.waitForIdleSync()
        }

        fun await(what: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var last: Throwable? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                last = null
                if (runCatching(predicate).onFailure { last = it }.getOrDefault(false)) return
                Thread.sleep(50)
            }
            // The observed requests and the last predicate fault make a timeout diagnosable.
            error("Timed out waiting for $what (requests ${host.requested.toList()}" +
                (last?.let { ", last error $it" } ?: "") + ")")
        }

        // Content: the controlled picture is drawn, and its alt text stays in the accessible tree.
        show(imageTree(host.origin + "/red.png", "Controlled red picture"))
        await("the red picture") { shows(frame(live.window), 255, 0, 0) }
        evidence.add("content: the red loopback picture was drawn")
        await("the alt text of the drawn picture") { "Controlled red picture" in descriptions(instrumentation) }
        evidence.add("accessibility: the drawn picture carries its alt text")

        // Failure: a missing resource draws no picture and states the failure with the alt text.
        show(imageTree(host.origin + "/missing.png", "Missing picture"))
        await("the failure message") { "Image unavailable" in descriptions(instrumentation) }
        check(!shows(frame(live.window), 255, 0, 0)) { "a failed resource must draw no picture" }
        check("Missing picture" in descriptions(instrumentation)) { "a failed resource keeps its alt text accessible" }
        evidence.add("failure: no picture drawn, alt text and the failure message accessible")

        // Replacement: a slow picture is superseded before it completes, so only the new picture shows.
        show(imageTree(host.origin + "/slow.png", "Slow picture"))
        await("the slow transfer to start") { "/slow.png" in host.requested }
        show(imageTree(host.origin + "/blue.png", "Replacement picture"))
        await("the replacement picture") { shows(frame(live.window), 0, 0, 255) }
        Thread.sleep(2000)
        val afterReplacement = frame(live.window)
        check(shows(afterReplacement, 0, 0, 255)) { "the replacement picture must stay displayed" }
        check(!shows(afterReplacement, 0, 255, 0)) { "the obsolete completion must not replace the current image" }
        evidence.add("replacement: the superseded slow picture never replaced the current one")

        // Removal: the node leaving the composition draws nothing and releases its picture.
        show(emptyTree())
        await("the removed picture") { !shows(frame(live.window), 0, 0, 255) }
        evidence.add("removal: the picture left the display with its node")

        return "image rendering: " + evidence.joinToString("; ") +
            " (requests " + host.requested.toList().distinct().joinToString(",") +
            "; peer writes " + host.writes.toList().joinToString(",") + ")"
    } finally {
        activity?.finish()
        host.close()
    }
}
