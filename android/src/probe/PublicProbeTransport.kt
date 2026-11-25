package dev.deal.embedding

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The public-probe transport. It is a mechanism, not policy: the isolate decides the destination,
 * redirect, media type, encoding, depth and selection rules, and this object only performs the one
 * credential-free HTTPS read that decision admitted.
 *
 * The transport still enforces the mechanical bounds — HTTPS, no redirect, the configured byte bound, one
 * wall-clock deadline and a raw byte body — and it resolves the destination once and refuses any answer
 * that is not a public unicast address. The connection implementation resolves the name again to open its
 * own socket, so the peer that socket really reached is validated as well and a nonpublic one fails the
 * read before any request byte is written: a name that answered a public address for the validation
 * lookup and a private one later cannot redirect the read. The reported `connected` address is that
 * socket's peer, never a second lookup.
 */
internal object PublicProbeTransport {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "embedding-probe-deadline").apply { isDaemon = true }
    }

    /** Whether every address the name resolves to is a public unicast destination. */
    fun publicDestination(addresses: List<InetAddress>): Boolean = addresses.isNotEmpty() && addresses.all { publicAddress(it) }

    /** Address-family classification of one parsed address, never of its spelling. */
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress) return false
        return when (address) {
            is Inet4Address -> {
                val bytes = address.address
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                first != 0 && first != 10 && first != 127 && first < 224 &&
                    !(first == 169 && second == 254) && !(first == 172 && second in 16..31) &&
                    !(first == 192 && second == 168) && !(first == 192 && second == 0 && bytes[2].toInt() in 0..2) &&
                    !(first == 100 && second in 64..127) && !(first == 198 && (second == 18 || second == 19)) &&
                    !(first == 198 && second == 51 && bytes[2].toInt() == 100) &&
                    !(first == 203 && second == 0 && bytes[2].toInt() == 113) &&
                    !(first == 192 && second == 88 && bytes[2].toInt() == 99)
            }
            is Inet6Address -> {
                val bytes = address.address
                val first = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
                val second = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
                // Global unicast is 2000::/3, so the range refuses the blocks a public JSON probe cannot reach —
                // the ones the platform's own flags do not cover — instead of the forms this file happens to
                // name: the second hextet of 2001:: is refused when it is 0x0000 (Teredo), 0x0002 (benchmarking,
                // refused as its whole 2001:2::/32), 0x0010-0x003f (the ORCHID and DET spans 2001:10::/28,
                // 2001:20::/28 and 2001:30::/28) or 0x0db8 (documentation), and 3fff::/16 is refused whole, which
                // contains documentation's 3fff::/20. The isolate binding refuses the same entries, and
                // tests/tools/fixtures/probe-destinations.txt is the one list both are executed against.
                (first and 0xe000) == 0x2000 && first != 0x3fff &&
                    !(first == 0x2001 && (second == 0x0db8 || second == 0x0000 || second == 0x0002 ||
                        (second >= 0x0010 && second <= 0x003f)))
            }
            else -> false
        }
    }

    /** One bounded read: the status, the declared media type, the connected address and the raw bytes. */
    fun read(url: String, maximumBytes: Int, timeoutMs: Int): JSONObject {
        val uri = java.net.URI(url)
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) throw PublicProbeFailure("PROBE_DESTINATION", "A public probe requires HTTPS")
        val port = if (uri.port > 0) uri.port else 443
        val addresses = InetAddress.getAllByName(uri.host).toList()
        if (!publicDestination(addresses)) throw PublicProbeFailure("PROBE_DESTINATION", "A public probe requires a public destination")
        val pinned = PinnedSocketFactory(addresses.first(), port, timeoutMs)
        val connection = uri.toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        connection.sslSocketFactory = pinned
        // A redirect would silently switch destination, so it fails instead of being followed.
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/json")
        val expired = AtomicBoolean(false)
        val deadline = deadlines.schedule({ expired.set(true); connection.disconnect() }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            val status = connection.responseCode
            val declared = connection.contentType ?: ""
            if (status != HttpURLConnection.HTTP_OK) return reply(status, declared, pinned, "")
            if (connection.contentLengthLong > maximumBytes) throw PublicProbeFailure("PROBE_LIMIT", "The probe response exceeds the configured byte bound")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { source ->
                val chunk = ByteArray(8192)
                while (true) {
                    if (expired.get()) throw PublicProbeFailure("PROBE_TIMEOUT", "The probe response was not read before its deadline")
                    val count = source.read(chunk)
                    if (count < 0) break
                    if (count > maximumBytes - output.size()) throw PublicProbeFailure("PROBE_LIMIT", "The probe response exceeds the configured byte bound")
                    output.write(chunk, 0, count)
                }
            }
            if (pinned.peers.isEmpty() || !pinned.peers.all { publicAddress(it) }) throw PublicProbeFailure("PROBE_DESTINATION", "A public probe requires a public destination")
            // One byte per code unit: the isolate performs the strict UTF-8 validation the specification
            // requires, so a differently encoded document is refused there instead of being reinterpreted.
            val bytes = output.toByteArray()
            val text = StringBuilder(bytes.size)
            for (byte in bytes) text.append((byte.toInt() and 0xff).toChar())
            return reply(status, declared, pinned, text.toString())
        } catch (error: PublicProbeFailure) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            throw PublicProbeFailure("PROBE_TIMEOUT", "The probe response was not read before its deadline")
        } catch (error: java.io.IOException) {
            throw PublicProbeFailure(if (expired.get()) "PROBE_TIMEOUT" else "PROBE_UNAVAILABLE", "The probe destination is unavailable")
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    /**
     * Validates the peer a connection implementation hands over in its layered form. The host and port
     * remain the name's, so SNI, `Host` and certificate checking authenticate the destination the source
     * named; the factory's own connect overloads (used only by a connection implementation that asks for
     * a socket instead of supplying one) open the address this read validated.
     */
    private class PinnedSocketFactory(private val target: InetAddress, private val defaultPort: Int, private val timeoutMs: Int) : SSLSocketFactory() {
        val peers: MutableList<InetAddress> = mutableListOf()
        override fun getDefaultCipherSuites(): Array<String> = default().defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = default().supportedCipherSuites
        override fun createSocket(): Socket = default().createSocket()
        override fun createSocket(host: String, port: Int): Socket = connect(port, host)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = connect(port, host)
        override fun createSocket(host: InetAddress, port: Int): Socket = connect(port, null)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = connect(port, null)
        // The layered form is handed a socket the connection implementation already connected to the address
        // it resolved itself. That socket's peer is the destination this read would really reach, so it is
        // validated and reported instead of trusted: a name that answered a public address for the validation
        // lookup and a private one for the connection is refused here.
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val peer = socket.inetAddress
            if (!socket.isConnected || peer == null || !publicAddress(peer)) {
                throw PublicProbeFailure("PROBE_DESTINATION", "A public probe requires a public destination")
            }
            val ssl = default().createSocket(socket, host.ifEmpty { target.hostAddress ?: "" }, if (port > 0) port else defaultPort, autoClose) as SSLSocket
            socket.soTimeout = timeoutMs
            identify(ssl, host)
            peers.add(peer)
            return ssl
        }
        private fun default(): SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        private fun connect(port: Int, host: String?): Socket {
            val socket = default().createSocket() as SSLSocket
            socket.connect(InetSocketAddress(target, if (port > 0) port else defaultPort), timeoutMs)
            socket.soTimeout = timeoutMs
            identify(socket, host)
            peers.add(socket.inetAddress)
            return socket
        }
        private fun identify(socket: SSLSocket, host: String?) {
            val parameters = socket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            if (host != null && host.isNotEmpty() && !host.startsWith("[")) parameters.serverNames = listOf(SNIHostName(host))
            socket.sslParameters = parameters
        }
    }

    private fun reply(status: Int, mediaType: String, pinned: PinnedSocketFactory, bytes: String): JSONObject =
        JSONObject().put("status", status).put("mediaType", mediaType)
            .put("connected", pinned.peers.firstOrNull()?.hostAddress ?: "").put("bytes", bytes)
}

internal class PublicProbeFailure(val code: String, message: String) : Exception(message)

/**
 * The probe lane of one live Session: probes are submitted off the UI lane and their replies are
 * delivered on a later advance, so a slow destination never blocks an action or a frame. One read is
 * pending at a time, which is the bound the isolate binding applies before it asks for another.
 */
internal class ProbeSession(private val limits: ExecutionLimits, private val closed: () -> Boolean = { false }) : AutoCloseable {
    private data class Finished(val id: Int, val reply: JSONObject)
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "embedding-probe-session").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<Int, java.util.concurrent.Future<*>>()
    private val finished = ConcurrentLinkedQueue<Finished>()

    fun receive(request: JSONObject) {
        check(!closed()) { "Probe session is closed" }
        val id = request.opt("id")
        require(id is Int && id > 0 && !pending.containsKey(id) && pending.size < 3) { "Invalid probe request" }
        val url = request.optString("url")
        val requested = request.optInt("maximumBytes", 0)
        val maximumBytes = if (requested > 0) minOf(requested, limits.probeBytes) else limits.probeBytes
        val timeoutMs = request.optInt("timeoutMs", 0)
        val timeout = if (timeoutMs > 0) minOf(timeoutMs.toLong(), limits.probeTimeoutMs) else limits.probeTimeoutMs
        pending[id] = io.submit {
            val reply = try { PublicProbeTransport.read(url, maximumBytes, timeout.toInt()).also { JSONObject().put("id", id).put("ok", true).put("value", it) } }
            catch (error: PublicProbeFailure) { JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", error.code).put("message", (error.message ?: "The probe failed").take(200))) }
            catch (error: Exception) { JSONObject().put("id", id).put("ok", false).put("error", JSONObject().put("code", "PROBE_UNAVAILABLE").put("message", (error.message ?: "The probe failed").take(200))) }
            finally { pending.remove(id) }
            finished.add(Finished(id, reply))
        }
    }

    fun drain(): List<JSONObject> {
        val replies = mutableListOf<JSONObject>()
        while (true) { replies.add(finished.poll()?.reply ?: break) }
        return replies
    }

    override fun close() {
        pending.values.forEach { it.cancel(true) }
        pending.clear(); finished.clear(); io.shutdownNow()
    }
}
