package dev.deal.embedding

/**
 * The one outbound transport rule for host-configured endpoints: HTTPS, or HTTP only on loopback,
 * with no embedded credentials and no fragment. Provider gateways and renderer resources share it,
 * so neither silently switches transport.
 */
internal object TransportPolicy {
    private val loopback = setOf("127.0.0.1", "localhost", "[::1]")

    /** The scheme and the host are case-insensitive, so a permitted endpoint is never failed for casing. */
    fun permits(scheme: String?, host: String?, userInfo: String?, fragment: String?): Boolean {
        val protocol = scheme?.lowercase()
        val destination = host?.lowercase()
        return !destination.isNullOrEmpty() && userInfo == null && fragment == null &&
            (protocol == "https" || protocol == "http" && destination in loopback)
    }

    /** Validates an absolute endpoint and returns it parsed; an impermissible one is rejected. */
    fun endpoint(value: String): java.net.URI {
        val uri = java.net.URI(value)
        require(uri.isAbsolute && !uri.host.isNullOrEmpty()) { "Invalid transport endpoint" }
        require(permits(uri.scheme, uri.host, uri.rawUserInfo, uri.rawFragment)) {
            "Transport requires HTTPS or loopback HTTP without credentials"
        }
        return uri
    }
}
