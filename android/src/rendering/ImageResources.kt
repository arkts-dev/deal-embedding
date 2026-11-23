package dev.deal.embedding

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The owned background lane for image transfers; a blocking read never runs on the UI thread. */
internal object ImageLane : java.util.concurrent.Executor {
    /** A small fixed pool bounds the transfers and decoded pictures alive at one time. */
    val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency) { task ->
        Thread(task, "embedding-image").apply { isDaemon = true }
    }

    override fun execute(command: Runnable) = pool.execute(command)
}

/** The delivery lane for image state changes: the UI thread, inline when already on it. */
internal object ImageDeliveryLane : java.util.concurrent.Executor {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun execute(command: Runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) command.run() else handler.post(command)
    }
}

/**
 * The host's configured permission for cleartext HTTP: loopback `http` resolves only for a host whose own
 * network security policy permits cleartext for that host, so a tree never widens the host's transport
 * configuration, no resource is requested on a refused host, and the renderer never switches transport to
 * work around a host configuration.
 */
internal object HostCleartextPolicy : (String) -> Boolean {
    override fun invoke(host: String): Boolean =
        runCatching { android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) }
            .getOrDefault(false)
}

/**
 * The renderer's owned image load, sized from the host-configured containment limits, four bytes per
 * pixel: one transfer is bounded by the smaller of the transfer bound and this lane's share of the heap
 * bound, and one decoded picture by that same share in pixels. The lane's concurrent transfers and the
 * pictures being decoded together peak at about half the heap bound, and a picture kept for display is
 * one such share released when its node replaces it or leaves, so a tree retains one bounded picture per
 * Image node. A load that exhausts native memory is a classified failure, never a truncated or oversized
 * picture. The image deadline is the host's evaluation bound.
 */
internal fun imageLoad(limits: ExecutionLimits, changed: () -> Unit): ImageLoad<Bitmap> {
    val budget = imageBudget(limits, ImageLane.concurrency)
    return ImageLoad(
        HttpImageTransport,
        BitmapImageDecoder(budget.maximumPixels),
        BitmapRelease,
        ImageLane,
        ImageDeliveryLane,
        budget.maximumBytes,
        budget.timeoutMs,
        HostCleartextPolicy,
        changed,
    )
}

/**
 * Bounded decode: the configured pixel bound limits the decoded picture by sampling, so a large
 * picture is never drawn beyond the containment limit, never truncated and never an out-of-memory.
 */
internal class BitmapImageDecoder(private val maximumPixels: Long) : ImageDecoder<Bitmap> {
    override fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong()
        var sample = 1L
        while (sample <= MAXIMUM_SAMPLE && pixels / (sample * sample) > maximumPixels) sample *= 2
        if (pixels / (sample * sample) > maximumPixels) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.toInt()
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 }
    }

    private companion object {
        const val MAXIMUM_SAMPLE = 1L shl 30
    }
}

/**
 * Releasing a decoded picture exactly once: the renderer drops its only reference, so the collector
 * reclaims the bounded picture. The bitmap is deliberately not recycled, because a frame recorded before
 * the replacement may still draw it and recycling a bitmap under a recorded draw crashes the renderer;
 * the per-picture bound keeps that delay immaterial.
 */
internal object BitmapRelease : ImageRelease<Bitmap> {
    override fun release(resource: Bitmap) = Unit
}
