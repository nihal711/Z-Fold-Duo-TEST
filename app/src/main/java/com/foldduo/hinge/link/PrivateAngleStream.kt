package com.foldduo.hinge.link

/**
 * Shell commands and log parsing for Samsung's private hinge-angle stream.
 *
 * `FoldInteractive`, the system wallpaper component, logs `mCurrentAngle[...]`
 * whenever it receives a wallpaper command. The probe loop sends that command
 * 40 times a second, which turns the log into a steady angle heartbeat.
 */
object PrivateAngleStream {
    const val ANGLE_ACTION = "zfoldduo_angle"

    const val PROBE_COMMAND =
        "while :; do service call wallpaper 90 i32 5 s16 $ANGLE_ACTION >/dev/null; sleep 0.025; done"

    /**
     * FoldInteractive normally unsubscribes from the sensor while the cover panel
     * is active. Its internal wake command re-registers it without touching
     * display power or topology.
     */
    const val SENSOR_WAKE_COMMAND =
        "service call wallpaper 90 i32 5 s16 android.wallpaper.wakingup >/dev/null"

    const val SENSOR_STOPPED_MARKER = "unregisterSensor: mIsSensorRegistered[true]"

    const val LOG_COMMAND =
        "logcat -v brief -T 1 --regex='(onCommand: action\\[$ANGLE_ACTION\\], mCurrentAngle|" +
            "unregisterSensor: mIsSensorRegistered\\[true\\])' " +
            "'SprWallpaper|FoldInteractive':I '*:S'"

    const val LIVE_CAPTURE_COMMAND =
        "CLASSPATH=${'$'}(pm path com.foldduo.hinge | head -n 1 | cut -d: -f2) " +
            "exec app_process /system/bin com.foldduo.hinge.capture.LiveCaptureBridge"

    /** Printed by the capture bridge once it is connected to the frame sink. */
    const val LIVE_READY_MARKER = "ZFoldDuo live capture ready"

    /** Printed by the capture bridge before it exits on an unrecoverable error. */
    const val LIVE_ERROR_MARKER = "ZFoldDuo live capture error"

    private val ANGLE = Regex("mCurrentAngle\\[([-+]?\\d+(?:\\.\\d+)?)\\]")

    /** Hinge angle in degrees clamped to 0..180, or null when the line is not an angle sample. */
    fun parseAngle(line: String): Float? {
        val value = ANGLE.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.coerceIn(0f, 180f)
    }

    fun isSensorStopped(line: String): Boolean = line.contains(SENSOR_STOPPED_MARKER)
}
