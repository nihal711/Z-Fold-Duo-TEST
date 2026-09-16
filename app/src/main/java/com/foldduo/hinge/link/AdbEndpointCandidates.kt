package com.foldduo.hinge.link

/** One adbd address worth trying, in priority order. */
data class AdbEndpoint(val host: String, val port: Int, val origin: Origin) {
    enum class Origin { MDNS, SYSTEM_PROPERTY, REMEMBERED }

    override fun toString(): String = "$host:$port ($origin)"
}

/**
 * Builds the ordered list of adbd endpoints to try.
 *
 * Wireless debugging listens on every interface, so the loopback address is
 * always preferred: it survives Wi-Fi IP changes, roaming and Android 17's
 * local-network permission, all of which drop a connection made to the
 * Wi-Fi address. The discovered address stays as a fallback for builds where
 * loopback is unexpectedly refused. The port announced through mDNS wins; the
 * `service.adb.tls.port` property and the last port that worked are used when
 * NsdManager loses the service, which it regularly does on Samsung builds.
 */
object AdbEndpointCandidates {
    const val LOOPBACK = "127.0.0.1"

    fun build(
        mdnsHost: String?,
        mdnsPort: Int?,
        propertyPort: Int?,
        rememberedPort: Int?,
    ): List<AdbEndpoint> {
        val result = ArrayList<AdbEndpoint>(6)
        fun add(host: String, port: Int?, origin: AdbEndpoint.Origin) {
            if (port == null || port !in 1..65535 || host.isBlank()) return
            if (result.any { it.host == host && it.port == port }) return
            result += AdbEndpoint(host, port, origin)
        }
        add(LOOPBACK, mdnsPort, AdbEndpoint.Origin.MDNS)
        add(LOOPBACK, propertyPort, AdbEndpoint.Origin.SYSTEM_PROPERTY)
        add(LOOPBACK, rememberedPort, AdbEndpoint.Origin.REMEMBERED)
        if (mdnsHost != null && mdnsHost != LOOPBACK && mdnsHost != "localhost") {
            add(mdnsHost, mdnsPort, AdbEndpoint.Origin.MDNS)
        }
        return result
    }

    /** Parses the output of `getprop service.adb.tls.port`; blank or garbage yields null. */
    fun parsePropertyPort(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
}
