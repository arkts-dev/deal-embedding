package dev.deal.embedding

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Deterministic items for the owned Image load: permitted sources, decoded content, explicit
 * failure, source replacement, obsolete completion and cleanup. Transfers and deliveries run on
 * lanes this item drains, so the ordering is explicit; the last items bind the production painter
 * to this mechanism and fail against the alt-text-only painter it replaces.
 */

/** One controlled 1x1 PNG: a real image input for the production transfer-and-decode pipeline. */
private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg=="

private val PIXEL = java.util.Base64.getDecoder().decode(PNG)

/** A lane the test drains explicitly; no task runs on the calling thread. */
private class Lane : Executor {
    private val tasks = mutableListOf<Runnable>()
    override fun execute(command: Runnable) { tasks.add(command) }
    val pending get() = tasks.size
    fun run(index: Int) { tasks.removeAt(index).run() }
    fun drain() { while (tasks.isNotEmpty()) run(0) }
}

/** One decoded picture; identity is what the release items observe. */
private class Picture(val source: ByteArray) { override fun toString() = "Picture(${source.size})" }

private class Recorder {
    val released = mutableListOf<Picture>()
    fun release(picture: Picture) { released.add(picture) }
}

/** Transport whose bytes and failures the item controls; it records the stop handle per source. */
private class Controlled : ImageTransport {
    val calls = mutableListOf<String>()
    val observedStopped = mutableListOf<Boolean>()
    var ignoreStop = false
    var failure: String? = null
    var bytes: (String) -> ByteArray = { PIXEL }
    override fun bytes(url: String, maximumBytes: Int, timeoutMs: Int, stop: ImageStop): ByteArray {
        calls.add(url)
        observedStopped.add(stop.isStopped)
        if (!ignoreStop && stop.isStopped) throw ImageFailure("IMAGE_CANCELLED")
        failure?.let { throw ImageFailure(it) }
        return bytes(url)
    }
}

/** Validates the controlled input as a real PNG and decodes one picture per input. */
private class Pictures : ImageDecoder<Picture> {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val decoded = mutableListOf<Picture>()
    override fun decode(bytes: ByteArray): Picture? {
        if (bytes.size < 33) return null
        for (index in signature.indices) if (bytes[index] != signature[index]) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        return Picture(bytes).also { decoded.add(it) }
    }
}

/** One rendered node's owned load over manual lanes. */
private class Owner(val maximumBytes: Int = 4096, cleartext: (String) -> Boolean = { true }) {
    val lane = Lane()
    val ui = Lane()
    val transport = Controlled()
    val recorder = Recorder()
    val pictures = Pictures()
    val states = mutableListOf<ImagePainting<Picture>>()
    var changes = 0
    val load: ImageLoad<Picture> = ImageLoad(
        transport, pictures, ImageRelease { recorder.release(it) }, lane, ui, maximumBytes, 1000, cleartext,
    ) {
        changes++
        states.add(load.painting)
    }
}

private fun content(painting: ImagePainting<Picture>, alt: String): Picture {
    check(painting is ImagePainting.Content && painting.alt == alt) { "expected content for $alt, got $painting" }
    return painting.resource
}

fun main(args: Array<String>) {
    val root = Path.of(args[0])

    // Permitted sources only: one transport rule, no silent switch, no credential, no fragment.
    run {
        val owner = Owner()
        for (url in listOf("https://images.example.test/pic.png", "http://127.0.0.1:8080/pic.png",
                           "http://localhost/pic.png", "http://[::1]/pic.png",
                           // Scheme and host are case-insensitive: a permitted endpoint is not failed for casing.
                           "HTTPS://Images.Example.Test/pic.png", "HTTP://127.0.0.1:8080/pic.png",
                           "http://LOCALHOST/pic.png")) {
            check(owner.load.source(url) == ImageSource.Permitted(url)) { "$url must be a permitted source" }
        }
        for (url in listOf("http://images.example.test/pic.png", "HTTP://Images.Example.Test/pic.png",
                           "file:///etc/hosts",
                           "content://media/external/images/1", "data:image/png;base64,AAAA",
                           "https://user:secret@images.example.test/pic.png",
                           "https://images.example.test/pic.png#fragment")) {
            check(owner.load.source(url) == ImageSource.Rejected("IMAGE_TRANSPORT")) {
                "$url must be refused rather than switching transport"
            }
        }
        for (src in listOf("", "   ", "not a url", "//images.example.test/pic.png")) {
            check(owner.load.source(src) == ImageSource.Rejected("IMAGE_SOURCE")) { "$src must be refused" }
        }
        check(owner.transport.calls.isEmpty()) { "resolving a source transfers nothing" }
    }

    // Loopback HTTP is the host's grant: without the host's cleartext permission the source is refused
    // before any request, and HTTPS keeps working so nothing switches transport on its behalf.
    run {
        val denied = Owner(cleartext = { false })
        for (url in listOf("http://127.0.0.1:8080/pic.png", "http://localhost/pic.png", "http://[::1]/pic.png")) {
            check(denied.load.source(url) == ImageSource.Rejected("IMAGE_TRANSPORT")) {
                "$url must be refused where the host does not permit cleartext"
            }
        }
        denied.load.show("http://127.0.0.1:8080/pic.png", "Loopback without the host grant")
        check(denied.load.painting == ImagePainting.Failed("Loopback without the host grant", "IMAGE_TRANSPORT")) {
            "a refused loopback source is an explicit failure, got ${denied.load.painting}"
        }
        denied.lane.drain()
        denied.ui.drain()
        check(denied.transport.calls.isEmpty()) { "a refused loopback source requests nothing" }
        denied.load.show("https://images.example.test/pic.png", "Secure picture")
        denied.lane.drain()
        denied.ui.drain()
        content(denied.load.painting, "Secure picture")
        check(denied.transport.calls == listOf("https://images.example.test/pic.png")) {
            "the host's cleartext policy must not affect HTTPS, or switch a refused source onto it"
        }
        val granted = Owner(cleartext = { it == "127.0.0.1" })
        check(granted.load.source("http://127.0.0.1:8080/pic.png") == ImageSource.Permitted("http://127.0.0.1:8080/pic.png")) {
            "the host's own cleartext grant permits its loopback host"
        }
        check(granted.load.source("http://localhost/pic.png") == ImageSource.Rejected("IMAGE_TRANSPORT")) {
            "a grant for one host does not widen to another, got ${granted.load.source("http://localhost/pic.png")}"
        }
        // The host's cleartext policy is asked about the normalized host, so casing never decides the grant.
        val caseFolded = Owner(cleartext = { it == "localhost" })
        check(caseFolded.load.source("http://LOCALHOST/pic.png") == ImageSource.Permitted("http://LOCALHOST/pic.png")) {
            "the cleartext policy must be asked about the normalized host"
        }
    }

    // Successful display: loading, then the decoded content, with the alt text kept in every state.
    run {
        val owner = Owner()
        check(owner.load.painting == ImagePainting.Blank("")) { "an unrequested node draws nothing" }
        owner.load.show("https://images.example.test/pic.png", "A live oak in summer")
        check(owner.load.painting == ImagePainting.Loading("A live oak in summer")) { "the loading state is explicit" }
        check(owner.transport.calls.isEmpty()) { "the blocking transfer never runs on the calling thread" }
        owner.lane.drain()
        check(owner.transport.calls.size == 1) { "the transfer runs on the owned background lane" }
        check(owner.load.painting == ImagePainting.Loading("A live oak in summer")) { "delivery runs on the owned delivery lane" }
        owner.ui.drain()
        val picture = content(owner.load.painting, "A live oak in summer")
        check(picture in owner.pictures.decoded) { "the displayed resource is the decoded picture" }
        check(owner.states.none { it is ImagePainting.Failed }) { "a valid picture is not reported as a failure" }
        check(owner.changes >= 3) { "each state change is announced" }
    }

    // Failure is explicit and keeps the alt text; a refused source starts no transfer.
    run {
        val owner = Owner()
        owner.transport.failure = "IMAGE_HTTP_404"
        owner.load.show("https://images.example.test/missing.png", "Missing picture")
        owner.lane.drain()
        owner.ui.drain()
        check(owner.load.painting == ImagePainting.Failed("Missing picture", "IMAGE_HTTP_404")) {
            "a failed transfer reports its classified failure, got ${owner.load.painting}"
        }
        owner.load.show("file:///etc/hosts", "Local file")
        check(owner.load.painting == ImagePainting.Failed("Local file", "IMAGE_TRANSPORT")) {
            "a refused source is an explicit failure"
        }
        check(owner.transport.calls.size == 1) { "a refused source transfers nothing" }
        owner.transport.failure = null
        owner.transport.bytes = { ByteArray(64) }
        owner.load.show("https://images.example.test/garbage.png", "Not an image")
        owner.lane.drain()
        owner.ui.drain()
        check(owner.load.painting == ImagePainting.Failed("Not an image", "IMAGE_DECODE")) { "undecodable bytes fail" }
        val bounded = Owner(maximumBytes = 40)
        bounded.transport.bytes = { ByteArray(41) }
        bounded.load.show("https://images.example.test/large.png", "Too large")
        bounded.lane.drain()
        bounded.ui.drain()
        check(bounded.load.painting == ImagePainting.Failed("Too large", "IMAGE_LIMIT")) {
            "a transfer beyond the configured bound fails rather than truncating"
        }
        check(bounded.pictures.decoded.isEmpty()) { "an over-limit transfer decodes nothing" }
    }

    // Source replacement: the replaced transfer is stopped before it reads and never paints over the new image.
    run {
        val owner = Owner()
        owner.load.show("https://images.example.test/first.png", "First")
        owner.load.show("https://images.example.test/second.png", "Second")
        owner.lane.drain()
        owner.ui.drain()
        content(owner.load.painting, "Second")
        check(owner.transport.observedStopped == listOf(true, false)) { "only the replaced transfer is stopped" }
    }

    // A stop aborts the transfer in flight: its registered action runs once and late registrations fire at once.
    run {
        val stop = ImageStop()
        var aborted = 0
        val registration = stop.onStop { aborted++ }
        check(!stop.isStopped && aborted == 0) { "an owned transfer is not stopped while it is current" }
        stop.stop()
        check(stop.isStopped && aborted == 1) { "stopping runs the transfer's abort action" }
        stop.stop()
        check(aborted == 1) { "a stopped transfer aborts once" }
        registration.close()
        val late = stop.onStop { aborted++ }
        check(aborted == 2) { "a registration after the stop aborts immediately" }
        late.close()
    }

    // An obsolete completion arriving after the replacement never replaces the current image or releases
    // the picture it displays; its own decoded picture is released instead.
    run {
        val owner = Owner()
        owner.transport.ignoreStop = true
        owner.load.show("https://images.example.test/slow.png", "Slow")
        owner.lane.run(0)
        owner.load.show("https://images.example.test/fast.png", "Fast")
        owner.lane.run(0)
        owner.ui.run(1)
        val fast = content(owner.load.painting, "Fast")
        check(owner.pictures.decoded.size == 2) { "both controlled inputs were decoded" }
        val slow = owner.pictures.decoded.single { it !== fast }
        owner.ui.run(0)
        check(content(owner.load.painting, "Fast") === fast) {
            "the obsolete completion must not replace the displayed picture, got ${owner.load.painting}"
        }
        check(owner.recorder.released == listOf(slow)) {
            "only the obsolete picture is released, got ${owner.recorder.released}"
        }
    }

    // An alt change on the same src relabels the displayed picture without a second transfer.
    run {
        val owner = Owner()
        owner.load.show("https://images.example.test/pic.png", "First label")
        owner.lane.drain()
        owner.ui.drain()
        val picture = content(owner.load.painting, "First label")
        owner.load.show("https://images.example.test/pic.png", "Second label")
        check(owner.load.painting == ImagePainting.Content("Second label", picture)) {
            "the same src keeps its picture and takes the new alt text, got ${owner.load.painting}"
        }
        check(owner.transport.calls.size == 1) { "an alt change transfers nothing" }
        check(owner.recorder.released.isEmpty()) { "an alt change releases nothing" }
    }

    // An alt change while the transfer is in flight is the description the completion carries.
    run {
        val owner = Owner()
        owner.load.show("https://images.example.test/pic.png", "First label")
        owner.lane.drain()
        owner.load.show("https://images.example.test/pic.png", "Second label")
        check(owner.load.painting == ImagePainting.Loading("Second label")) {
            "the loading state takes the new alt text, got ${owner.load.painting}"
        }
        owner.ui.drain()
        content(owner.load.painting, "Second label")
        check(owner.transport.calls.size == 1) { "an alt change while loading transfers nothing" }
    }

    // Disposal: the displayed resource is released exactly once and no later request or completion draws.
    run {
        val owner = Owner()
        owner.load.show("https://images.example.test/pic.png", "Picture")
        owner.lane.drain()
        owner.ui.drain()
        val picture = content(owner.load.painting, "Picture")
        check(owner.recorder.released.isEmpty()) { "a displayed resource stays owned" }
        owner.load.dispose()
        check(owner.recorder.released == listOf(picture)) { "disposal releases the displayed resource" }
        check(owner.load.painting is ImagePainting.Blank) { "a disposed node draws nothing" }
        owner.load.show("https://images.example.test/after.png", "After disposal")
        check(owner.transport.calls.size == 1) { "a disposed node starts no request" }
        owner.load.dispose()
        check(owner.recorder.released.size == 1) { "release happens exactly once" }
    }

    // A completion queued before disposal is dropped and its picture released.
    run {
        val owner = Owner()
        owner.transport.ignoreStop = true
        owner.load.show("https://images.example.test/pic.png", "In flight")
        owner.lane.drain()
        owner.load.dispose()
        owner.ui.drain()
        check(owner.load.painting is ImagePainting.Blank) { "an obsolete completion after disposal draws nothing" }
        check(owner.recorder.released.size == 1) { "the dropped picture is released" }
    }

    // Identity loss: the node leaving the composition releases its resource; a new node owns a new load.
    run {
        val owner = Owner()
        owner.load.show("https://images.example.test/pic.png", "Retained")
        owner.lane.drain()
        owner.ui.drain()
        content(owner.load.painting, "Retained")
        owner.load.dispose()
        check(owner.recorder.released.size == 1) { "identity loss releases the resource" }
        check(owner.load.painting is ImagePainting.Blank) { "identity loss draws nothing new" }
        val returned = Owner()
        returned.load.show("https://images.example.test/returned.png", "Returned")
        returned.lane.drain()
        returned.ui.drain()
        content(returned.load.painting, "Returned")
    }

    // The configured containment bounds one owned load: four bytes per pixel, one lane share of the heap
    // bound per transfer and per decoded picture, and the host evaluation bound as the transfer deadline.
    run {
        val limits = ExecutionLimits(evaluationTimeoutMs = 12_000, heapBytes = 64L * 1024 * 1024, transferBytes = 16 * 1024 * 1024)
        val budget = imageBudget(limits, 4)
        val share = limits.heapBytes / 4 / 4
        check(budget.timeoutMs == 12_000) { "the deadline is the host evaluation bound, got ${budget.timeoutMs}" }
        check(budget.maximumBytes.toLong() == share) { "one transfer takes one lane share, got ${budget.maximumBytes}" }
        check(budget.maximumPixels == share / 4) { "one picture takes that share at four bytes per pixel, got ${budget.maximumPixels}" }
        check(4L * budget.maximumBytes <= limits.heapBytes / 4) { "concurrent transfers stay within a quarter of the heap bound" }
        check(4L * budget.maximumPixels * 4 <= limits.heapBytes / 4) { "concurrent decodes stay within a quarter of the heap bound" }
        // A smaller host transfer bound wins, and a degenerate host bound still yields usable bounds.
        check(imageBudget(ExecutionLimits(heapBytes = 64L * 1024 * 1024, transferBytes = 1024), 2).maximumBytes == 1024) {
            "the smaller host transfer bound must win"
        }
        val tiny = imageBudget(ExecutionLimits(evaluationTimeoutMs = 1, heapBytes = 1, transferBytes = 1), 4)
        check(tiny == ImageBudget(1, 1, 1)) { "a tiny host bound still yields positive bounds, got $tiny" }
        check(imageBudget(ExecutionLimits(evaluationTimeoutMs = 90_000), 3).timeoutMs == 90_000) {
            "the host deadline passes through unchanged"
        }
    }

    // The production painter resolves src and draws content through this mechanism, not alt text alone.
    val renderer = Files.readString(root.resolve("android/src/rendering/DealRenderer.kt"))
    check("RenderComponent.Image -> ImageContent(values.text(\"src\"), values.text(\"alt\"))" in renderer) {
        "the Image painter must resolve src and draw its content"
    }
    check("Text(values.text(\"alt\"), style = PackTheme.caption, color = PackTheme.muted)" !in renderer) {
        "the alt-text-only Image painter is the omission this change replaces"
    }
    check("imageLoad(limits)" in renderer) { "the painter must own the production image load" }
    check("load.dispose()" in renderer) { "the painter must dispose its owned load" }
    check("LaunchedEffect(load, src, alt)" in renderer) { "the painter must request its own load" }

    println("image load: permitted sources, content, failure, replacement, obsolete completion and cleanup items passed")
}
