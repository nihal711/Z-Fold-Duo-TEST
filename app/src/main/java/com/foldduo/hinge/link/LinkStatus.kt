package com.foldduo.hinge.link

/** Where the current hinge angle comes from. */
enum class AngleSource {
    /** Samsung's private high-rate hinge sensor, read through the on-device ADB link. */
    PRIVATE_ADB,

    /** The public Android `TYPE_HINGE_ANGLE` sensor. Coarser, but needs no ADB. */
    PUBLIC_SENSOR,
}

/** State of the on-device ADB link. Rendered by the UI through string resources. */
sealed class LinkStatus {
    /** No wireless-debugging endpoint has been discovered yet. */
    data object WaitingForWirelessDebugging : LinkStatus()

    /** A TCP connection to adbd is being set up. */
    data class Connecting(val host: String, val port: Int) : LinkStatus()

    /** The device rejected our certificate: the user has to pair again. */
    data object PairingRequired : LinkStatus()

    data object Pairing : LinkStatus()

    data object Paired : LinkStatus()

    data class PairingFailed(val reason: String) : LinkStatus()

    /** Connected. [angleLive] and [captureLive] describe the two long-lived shell streams. */
    data class Connected(
        val host: String,
        val port: Int,
        val angleLive: Boolean,
        val captureLive: Boolean,
        /** Human-readable note about the private angle stream (probe transaction, self-test result). */
        val angleDetail: String? = null,
        /** Last line printed by the live-capture bridge; explains why capture is unavailable. */
        val captureDetail: String? = null,
    ) : LinkStatus()

    /** A connection attempt failed for a reason other than authentication; we retry. */
    data class Retrying(val reason: String, val nextAttemptInMs: Long) : LinkStatus()

    /** An established link was lost. */
    data class Disconnected(val reason: String) : LinkStatus()

    val isConnected: Boolean get() = this is Connected
}
