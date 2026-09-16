package com.foldduo.hinge

import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import com.foldduo.hinge.link.PrivateAngleStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads Samsung's private folding-angle sensor through its system wallpaper
 * engine and hosts the shell-side live-capture bridge, all over one on-device
 * ADB session.
 *
 * The session owns three long-lived shell streams. Each stream is supervised
 * independently: when the remote command exits (logcat killed, capture bridge
 * crashed, probe loop terminated) only that stream is restarted, with backoff.
 * The session as a whole is only torn down when a stream can no longer be
 * opened, which means adbd itself is gone. The first release tore everything
 * down whenever any single stream ended, which on the Fold8 Ultra showed up
 * as constant "disconnect / reconnect" cycles and a vanishing angle readout.
 */
class EmbeddedAdbAngleClient(
    private val onEvent: (Event) -> Unit,
    private val onAngle: (angle: Float, timestampNanos: Long) -> Unit,
) : Closeable {
    sealed class Event {
        data class Connected(val host: String, val port: Int) : Event()

        /** Liveness of the two streams the UI cares about. */
        data class Streams(val angleLive: Boolean, val captureLive: Boolean) : Event()

        /** The session is gone. The owner decides when and where to reconnect. */
        data class Lost(val reason: String) : Event()
    }

    private val controlLock = Any()

    @Volatile
    private var session: Session? = null

    val isConnected: Boolean
        get() = session?.alive?.get() == true

    /** Runs a short command on the control connection. Fails fast when disconnected. */
    fun shell(command: String): Result<String> {
        val current = session?.takeIf { it.alive.get() }
            ?: return Result.failure(IllegalStateException("ADB is not connected"))
        return synchronized(controlLock) {
            runCatching { current.control.shell(command).allOutput }
        }.onFailure { error ->
            if (current.alive.get() && isTransportFailure(error)) {
                lose(current, "control channel failed: ${safeMessage(error)}")
            }
        }
    }

    /**
     * Connects to adbd at [host]:[port]. Throws when the transport or the
     * authentication fails; the caller classifies the exception.
     */
    fun connect(host: String, port: Int) {
        close()
        val data = Kadb.create(host, port, connectTimeout = CONNECT_TIMEOUT_MS)
        val control = Kadb.create(host, port, connectTimeout = CONNECT_TIMEOUT_MS)
        try {
            check(data.shell("echo ZFoldDuo").allOutput.trim() == "ZFoldDuo") { "unexpected shell reply" }
            check(control.shell("echo ZFoldDuo-control").allOutput.trim() == "ZFoldDuo-control") {
                "unexpected control shell reply"
            }
        } catch (error: Throwable) {
            runCatching { data.close() }
            runCatching { control.close() }
            throw error
        }
        val started = Session(host, port, data, control)
        session = started
        Log.i(TAG, "ADB session established at $host:$port")
        onEvent(Event.Connected(host, port))

        // FoldInteractive normally unsubscribes while the cover panel is active.
        // Its internal wake command re-enables the same private sensor without
        // changing either display's power or topology.
        runCatching { control.shell(PrivateAngleStream.SENSOR_WAKE_COMMAND) }

        started.start("probe") { runProbe(started) }
        started.start("angle") { runAngle(started) }
        started.start("live") { runLiveCapture(started) }
        started.start("watchdog") { runWatchdog(started) }
    }

    override fun close() {
        val current = session ?: return
        session = null
        current.shutdown()
    }

    private fun runProbe(s: Session) {
        supervise(
            s,
            name = "probe",
            command = PrivateAngleStream.PROBE_COMMAND,
            backoff = Backoff(250L, 2_000L),
            onOpen = { s.probe = it },
            onLine = { /* the probe loop is silent; output only appears on errors */
                Log.w(TAG, "probe: $it")
            },
        )
    }

    private fun runAngle(s: Session) {
        supervise(
            s,
            name = "angle",
            command = PrivateAngleStream.LOG_COMMAND,
            backoff = Backoff(250L, 2_000L),
            onOpen = { s.angle = it },
            onLine = { line ->
                if (PrivateAngleStream.isSensorStopped(line)) {
                    shell(PrivateAngleStream.SENSOR_WAKE_COMMAND)
                    return@supervise
                }
                val angle = PrivateAngleStream.parseAngle(line) ?: return@supervise
                s.lastAngleAtMs = SystemClock.uptimeMillis()
                s.everHadAngle = true
                onAngle(angle, SystemClock.elapsedRealtimeNanos())
                if (!s.angleLive) {
                    s.angleLive = true
                    s.stalls = 0
                    publishStreams(s)
                }
            },
        )
    }

    private fun runLiveCapture(s: Session) {
        supervise(
            s,
            name = "live",
            command = PrivateAngleStream.LIVE_CAPTURE_COMMAND,
            backoff = Backoff(1_000L, 15_000L),
            onOpen = { s.live = it },
            onLine = { line ->
                Log.i(TAG, "capture: $line")
                if (line.contains(PrivateAngleStream.LIVE_READY_MARKER) && !s.captureLive) {
                    s.captureLive = true
                    publishStreams(s)
                }
            },
            onExit = {
                if (s.captureLive) {
                    s.captureLive = false
                    publishStreams(s)
                }
            },
        )
    }

    /**
     * The probe makes FoldInteractive log an angle 40 times a second, so a
     * silent angle stream means the probe or logcat is wedged even though its
     * stream is still open. Restart both; only give up on the session when
     * repeated restarts do not bring the heartbeat back.
     */
    private fun runWatchdog(s: Session) {
        while (s.alive.get()) {
            if (!s.sleep(WATCHDOG_INTERVAL_MS)) return
            val now = SystemClock.uptimeMillis()
            val silentFor = now - s.lastAngleAtMs
            if (s.angleLive && silentFor > ANGLE_STALL_MS) {
                s.angleLive = false
                s.stalls++
                publishStreams(s)
                if (s.stalls >= MAX_ANGLE_STALLS) {
                    lose(s, "hinge angle stream stalled ${s.stalls} times")
                    return
                }
                Log.w(TAG, "angle heartbeat silent for ${silentFor}ms; restarting probe and log streams (stall ${s.stalls})")
                shell(PrivateAngleStream.SENSOR_WAKE_COMMAND)
                runCatching { s.probe?.close() }
                runCatching { s.angle?.close() }
            } else if (!s.angleLive && !s.everHadAngle && silentFor > ANGLE_ABSENT_MS && !s.absenceLogged) {
                s.absenceLogged = true
                Log.w(
                    TAG,
                    "no private hinge angle after ${silentFor}ms; this build may not expose FoldInteractive. " +
                        "Falling back to the public hinge sensor while keeping display control.",
                )
            }
        }
    }

    private fun supervise(
        s: Session,
        name: String,
        command: String,
        backoff: Backoff,
        onOpen: (AdbStream) -> Unit,
        onLine: (String) -> Unit,
        onExit: () -> Unit = {},
    ) {
        var restarts = 0
        while (s.alive.get()) {
            val stream = try {
                synchronized(s.openLock) { s.data.open("shell:$command") }
            } catch (error: Throwable) {
                // Kadb already re-dialled the transport once before giving up,
                // so a failed open means adbd is unreachable.
                lose(s, "$name stream could not be opened: ${safeMessage(error)}")
                return
            }
            onOpen(stream)
            var failure: Throwable? = null
            try {
                while (s.alive.get()) {
                    val line = stream.source.readUtf8Line() ?: break
                    onLine(line)
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                runCatching { stream.close() }
            }
            if (!s.alive.get()) return
            restarts++
            onExit()
            val wait = backoff.delayFor(restarts)
            Log.w(TAG, "$name stream ended (${failure?.let(::safeMessage) ?: "exit"}); restart #$restarts in ${wait}ms")
            if (!s.sleep(wait)) return
        }
    }

    private fun publishStreams(s: Session) {
        if (s.alive.get()) onEvent(Event.Streams(s.angleLive, s.captureLive))
    }

    private fun lose(s: Session, reason: String) {
        if (!s.alive.compareAndSet(true, false)) return
        if (session === s) session = null
        s.closeAll()
        Log.w(TAG, "ADB session lost: $reason")
        onEvent(Event.Lost(reason))
    }

    private class Session(
        val host: String,
        val port: Int,
        val data: Kadb,
        val control: Kadb,
    ) {
        val alive = AtomicBoolean(true)
        val openLock = Any()
        private val threads = mutableListOf<Thread>()

        @Volatile var probe: AdbStream? = null
        @Volatile var angle: AdbStream? = null
        @Volatile var live: AdbStream? = null
        @Volatile var lastAngleAtMs: Long = SystemClock.uptimeMillis()
        @Volatile var angleLive = false
        @Volatile var captureLive = false
        @Volatile var everHadAngle = false
        @Volatile var absenceLogged = false
        @Volatile var stalls = 0

        fun start(name: String, body: () -> Unit) {
            val thread = Thread({
                try {
                    body()
                } catch (error: Throwable) {
                    Log.e(TAG, "$name worker crashed", error)
                }
            }, "ZFoldDuo-adb-$name")
            thread.isDaemon = true
            synchronized(threads) { threads += thread }
            thread.start()
        }

        /** Sleeps in short slices so shutdown is noticed quickly. Returns false once dead. */
        fun sleep(ms: Long): Boolean {
            val end = SystemClock.uptimeMillis() + ms
            while (alive.get()) {
                val remaining = end - SystemClock.uptimeMillis()
                if (remaining <= 0) return true
                try {
                    Thread.sleep(remaining.coerceAtMost(SLEEP_SLICE_MS))
                } catch (_: InterruptedException) {
                    return alive.get()
                }
            }
            return false
        }

        fun shutdown() {
            if (!alive.compareAndSet(true, false)) return
            closeAll()
        }

        fun closeAll() {
            runCatching { probe?.close() }
            runCatching { angle?.close() }
            runCatching { live?.close() }
            runCatching { control.close() }
            runCatching { data.close() }
            synchronized(threads) { threads.forEach(Thread::interrupt) }
        }
    }

    private class Backoff(private val initialMs: Long, private val maxMs: Long) {
        fun delayFor(attempt: Int): Long {
            var delay = initialMs
            repeat((attempt - 1).coerceIn(0, 16)) { delay = (delay * 2).coerceAtMost(maxMs) }
            return delay.coerceAtMost(maxMs)
        }
    }

    companion object {
        private const val TAG = "ZFoldDuoEngine"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SLEEP_SLICE_MS = 100L
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val ANGLE_STALL_MS = 3_000L
        private const val ANGLE_ABSENT_MS = 8_000L
        private const val MAX_ANGLE_STALLS = 3

        private fun safeMessage(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        private val TRANSPORT_FAILURE_MARKERS = listOf(
            "broken pipe", "connection reset", "connection aborted", "socket closed",
            "transport closed", "eof", "connection refused", "connection closed",
        )

        /**
         * True when the underlying socket is gone. A CLSE for a rejected shell
         * OPEN (Kadb's AdbStreamClosed) is not a transport failure and must not
         * tear the session down.
         */
        private fun isTransportFailure(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.any { cause ->
                when {
                    cause.javaClass.simpleName == "AdbStreamClosed" -> false
                    cause is com.flyfishxu.kadb.exception.AdbPairAuthException -> false
                    cause is java.net.SocketException || cause is java.io.EOFException -> true
                    cause is java.net.SocketTimeoutException -> true
                    cause is java.io.IOException ->
                        TRANSPORT_FAILURE_MARKERS.any { cause.message.orEmpty().lowercase().contains(it) }
                    else -> false
                }
            }
    }
}
