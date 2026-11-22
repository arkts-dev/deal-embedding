package dev.deal.embedding

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** The resolved `src` of one Image node. */
internal sealed interface ImageSource {
    /** The src names a permitted resource: the one transport rule accepted it. */
    data class Permitted(val url: String) : ImageSource

    /** The src is not a permitted resource; the node shows an explicit failure and starts no transfer. */
    data class Rejected(val code: String) : ImageSource
}

/**
 * Cancellation for one owned transfer. Closing it aborts the transfer in flight and discards its
 * bytes, so a replaced, removed or disposed node stops reading instead of finishing work nobody shows.
 */
internal class ImageStop {
    private val stopped = AtomicBoolean(false)
    private val actions = mutableSetOf<() -> Unit>()

    val isStopped: Boolean get() = stopped.get()

    /** Registration is atomic with cancellation; the caller closes it when its transfer ends. */
    @Synchronized fun onStop(action: () -> Unit): AutoCloseable {
        if (stopped.get()) {
            runCatching { action() }
            return AutoCloseable { }
        }
        actions.add(action)
        return AutoCloseable { synchronized(this) { actions.remove(action) } }
    }

    @Synchronized fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val active = actions.toList()
        actions.clear()
        active.forEach { runCatching { it() } }
    }
}

/** A classified image failure; the code is shown as the node's explicit failure state. */
internal class ImageFailure(val code: String) : Exception(code)

/** Blocking transfer of one permitted resource; called on the owned background lane, never the UI thread. */
internal fun interface ImageTransport {
    fun bytes(url: String, maximumBytes: Int, timeoutMs: Int, stop: ImageStop): ByteArray
}

/** Decoding transferred bytes into one drawable resource; null means the bytes are not an image. */
internal fun interface ImageDecoder<R : Any> {
    fun decode(bytes: ByteArray): R?
}

/** Releasing one decoded resource exactly once. */
internal fun interface ImageRelease<R : Any> {
    fun release(resource: R)
}

/** The configured per-resource bounds of one owned image load, four bytes per pixel. */
internal data class ImageBudget(val maximumBytes: Int, val maximumPixels: Long, val timeoutMs: Int)

/**
 * Sizes one owned load from the host's containment limits: one transfer takes the smaller of the transfer
 * bound and one lane share of the heap bound, one decoded picture takes that same share in pixels at four
 * bytes per pixel, and the host's evaluation bound is the transfer deadline. Nothing degenerates to zero.
 */
internal fun imageBudget(limits: ExecutionLimits, concurrency: Int): ImageBudget {
    val share = (limits.heapBytes / 4 / concurrency.coerceAtLeast(1)).coerceAtLeast(1L)
    return ImageBudget(
        share.coerceAtMost(limits.transferBytes.toLong()).toInt(),
        (share / 4).coerceAtLeast(1L),
        limits.evaluationTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
    )
}

/** One owned completion; only the delivery whose generation is still current may be painted. */
internal sealed interface ImageDelivery<out R : Any> {
    val generation: Long

    data class Loaded<R : Any>(override val generation: Long, val resource: R) : ImageDelivery<R>
    data class Failed(override val generation: Long, val code: String) : ImageDelivery<Nothing>
}

/** What a rendered Image node shows; the alt text is carried in every state. */
internal sealed interface ImagePainting<out R : Any> {
    val alt: String

    /** No request is owned: nothing is drawn and the alt text is the only accessible content. */
    data class Blank(override val alt: String) : ImagePainting<Nothing>

    /** An owned transfer is in flight. */
    data class Loading(override val alt: String) : ImagePainting<Nothing>

    /** The decoded resource to draw. */
    data class Content<R : Any>(override val alt: String, val resource: R) : ImagePainting<R>

    /** The transfer finished without a picture, or the source was refused; `alt` stays accessible. */
    data class Failed(override val alt: String, val code: String) : ImagePainting<Nothing>
}

/**
 * Owns at most one image request per rendered node. Every source replacement, identity loss and
 * disposal stops the in-flight transfer and releases its decoded resource, and an obsolete
 * completion never replaces the image the node shows.
 *
 * Blocking transfers run on [lane]; every state change runs on [ui], the UI thread in production,
 * so the current painting and the resource it holds are confined to that one thread. Concurrent
 * loads are allowed; `show` and `dispose` are called on [ui].
 */
internal class ImageLoad<R : Any>(
    private val transport: ImageTransport,
    private val decoder: ImageDecoder<R>,
    private val release: ImageRelease<R>,
    private val lane: Executor,
    private val ui: Executor,
    private val maximumBytes: Int,
    private val timeoutMs: Int,
    /** The host's configured permission for cleartext HTTP on one host; a tree never widens it. */
    private val cleartext: (String) -> Boolean,
    private val changed: () -> Unit,
) {
    private var generation = 0L
    private var owned: String? = null
    private var currentAlt = ""
    private var active: ImageStop? = null
    private var disposed = false

    /** The painting to draw; read on the delivery lane. */
    var painting: ImagePainting<R> = ImagePainting.Blank("")
        private set

    /** Resolves a src through the one transport rule: no silent switch and no invented permission or grant. */
    fun source(src: String): ImageSource {
        if (src.isBlank()) return ImageSource.Rejected("IMAGE_SOURCE")
        val uri = runCatching { java.net.URI(src) }.getOrNull() ?: return ImageSource.Rejected("IMAGE_SOURCE")
        if (!uri.isAbsolute) return ImageSource.Rejected("IMAGE_SOURCE")
        if (!TransportPolicy.permits(uri.scheme, uri.host, uri.rawUserInfo, uri.rawFragment)) {
            return ImageSource.Rejected("IMAGE_TRANSPORT")
        }
        // Loopback HTTP is the host's to grant: only the host's own network security policy decides
        // whether cleartext for that host is allowed, and tree-authored src never widens it.
        if (uri.scheme.equals("http", ignoreCase = true) && !cleartext(uri.host.lowercase())) {
            return ImageSource.Rejected("IMAGE_TRANSPORT")
        }
        return ImageSource.Permitted(src)
    }

    /** Replaces the owned request with the node's committed src and alt. */
    fun show(src: String, alt: String) {
        if (disposed) return
        currentAlt = alt
        if (src == owned) {
            // The same src keeps its owned transfer: only the accessible description changes.
            if (painting.alt != alt) publish(relabel(painting, alt))
            return
        }
        cancel(alt)
        owned = src
        val request = ++generation
        when (val source = source(src)) {
            is ImageSource.Rejected -> publish(ImagePainting.Failed(alt, source.code))
            is ImageSource.Permitted -> {
                val stop = ImageStop()
                active = stop
                publish(ImagePainting.Loading(alt))
                val url = source.url
                lane.execute { transfer(request, url, stop) }
            }
        }
    }

    /** The node left the composition: stop its transfer, release what it displayed and ignore later completions. */
    fun dispose() {
        if (disposed) return
        cancel(painting.alt)
        disposed = true
    }

    private fun cancel(alt: String) {
        generation++
        currentAlt = alt
        owned = null
        active?.stop()
        active = null
        val previous = painting
        if (previous is ImagePainting.Content) release.release(previous.resource)
        publish(ImagePainting.Blank(alt))
    }

    private fun relabel(painting: ImagePainting<R>, alt: String): ImagePainting<R> = when (painting) {
        is ImagePainting.Blank -> ImagePainting.Blank(alt)
        is ImagePainting.Loading -> ImagePainting.Loading(alt)
        is ImagePainting.Content -> ImagePainting.Content(alt, painting.resource)
        is ImagePainting.Failed -> ImagePainting.Failed(alt, painting.code)
    }

    private fun publish(next: ImagePainting<R>) {
        painting = next
        changed()
    }

    private fun transfer(request: Long, url: String, stop: ImageStop) {
        val delivery = try {
            val bytes = transport.bytes(url, maximumBytes, timeoutMs, stop)
            when {
                // A stopped transfer reads nothing further and publishes nothing.
                stop.isStopped -> return
                bytes.size > maximumBytes -> ImageDelivery.Failed(request, "IMAGE_LIMIT")
                else -> decoder.decode(bytes)?.let { ImageDelivery.Loaded(request, it) }
                    ?: ImageDelivery.Failed(request, "IMAGE_DECODE")
            }
        } catch (error: ImageFailure) {
            ImageDelivery.Failed(request, error.code)
        } catch (error: Throwable) {
            ImageDelivery.Failed(request, if (stop.isStopped) "IMAGE_CANCELLED" else "IMAGE_UNAVAILABLE")
        }
        ui.execute { complete(delivery) }
    }

    private fun complete(delivery: ImageDelivery<R>) {
        // Obsolete completions change nothing and release the resource nobody will draw.
        if (disposed || delivery.generation != generation) {
            (delivery as? ImageDelivery.Loaded)?.let { release.release(it.resource) }
            return
        }
        active = null
        val previous = painting
        val next: ImagePainting<R> = when (delivery) {
            // The accessible description is the node's current one, not the one the transfer started with.
            is ImageDelivery.Loaded -> ImagePainting.Content(currentAlt, delivery.resource)
            is ImageDelivery.Failed -> ImagePainting.Failed(currentAlt, delivery.code)
        }
        if (previous is ImagePainting.Content) release.release(previous.resource)
        publish(next)
    }
}
