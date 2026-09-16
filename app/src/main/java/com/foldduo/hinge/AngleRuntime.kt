package com.foldduo.hinge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbPairAuthException
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.foldduo.hinge.link.AdbEndpoint
import com.foldduo.hinge.link.AdbEndpointCandidates
import com.foldduo.hinge.link.AngleSource
import com.foldduo.hinge.link.DeviceStateCatalog
import com.foldduo.hinge.link.HingeAngleSensorSource
import com.foldduo.hinge.link.LinkStatus
import com.foldduo.hinge.link.PairingNotifier
import com.foldduo.hinge.link.ShellWakeLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class AngleSample(
    val angle: Float,
    val timestampNanos: Long,
    val source: AngleSource = AngleSource.PRIVATE_ADB,
)

/** One process-wide ADB connection shared by the activity and accessibility overlay. */
object AngleRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private val displayCommand = Mutex()
    private val retryKick = AtomicInteger(0)
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.WaitingForWirelessDebugging)
    private val _sample = MutableStateFlow<AngleSample?>(null)
    private val _pairEndpoint = MutableStateFlow<MdnsEndpoint?>(null)
    private val _connectEndpoint = MutableStateFlow<MdnsEndpoint?>(null)
    private val _deviceStates = MutableStateFlow(DeviceStateCatalog.EMPTY)

    val status = _status.asStateFlow()
    val sample = _sample.asStateFlow()
    val pairEndpoint = _pairEndpoint.asStateFlow()
    val connectEndpoint = _connectEndpoint.asStateFlow()
    val deviceStates = _deviceStates.asStateFlow()

    /** True while shell commands can be issued, which is what the display state machine needs. */
    val displayControlAvailable: Boolean
        get() = initialized && client.isConnected

    val publicSensorAvailable: Boolean
        get() = initialized && sensor.isAvailable

    val publicSensorDescription: String
        get() = if (initialized) sensor.description else "unavailable"

    private var initialized = false
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var mdns: KadbMdnsAndroid
    private lateinit var client: EmbeddedAdbAngleClient
    private lateinit var sensor: HingeAngleSensorSource

    @Volatile
    private var privateAngleLive = false

    @Volatile
    private var propertyPortCache: Pair<Long, Int?>? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("adb_link", Context.MODE_PRIVATE)
        KadbCertSetup.configure(app.filesDir)
        sensor = HingeAngleSensorSource(app) { angle, timestamp ->
            if (!privateAngleLive) _sample.value = AngleSample(angle, timestamp, AngleSource.PUBLIC_SENSOR)
        }
        client = EmbeddedAdbAngleClient(
            onEvent = ::onClientEvent,
            onAngle = { angle, timestamp ->
                _sample.value = AngleSample(angle, timestamp, AngleSource.PRIVATE_ADB)
            },
        )
        startPublicSensor()
        mdns = KadbMdnsAndroid(
            app,
            MdnsConfig(setOf(MdnsServiceType.TLS_CONNECT, MdnsServiceType.TLS_PAIRING)),
        )
        scope.launch {
            mdns.state.collect { state ->
                val pair = state.pairDevices.firstOrNull()
                val connect = state.connectDevices.firstOrNull()
                if (connect != null && connect != _connectEndpoint.value) kickRetry()
                _pairEndpoint.value = pair
                _connectEndpoint.value = connect
                if (pair != null && !client.isConnected) {
                    PairingNotifier.showCodePrompt(app, pair.port)
                } else if (pair == null && _status.value !is LinkStatus.Pairing) {
                    PairingNotifier.dismissPrompt(app)
                }
            }
        }
        mdns.start()
        scope.launch { connectionLoop() }
    }

    /** Wakes the connection loop early, e.g. after pairing or when a new endpoint appears. */
    fun kickRetry() {
        retryKick.incrementAndGet()
    }

    private suspend fun connectionLoop() {
        var attempt = 0
        while (scope.isActive) {
            if (client.isConnected) {
                attempt = 0
                delay(1_000L)
                continue
            }
            val mdns = _connectEndpoint.value
            val candidates = AdbEndpointCandidates.build(
                mdnsHost = mdns?.host,
                mdnsPort = mdns?.port,
                propertyPort = readPropertyPort(),
                rememberedPort = rememberedPort(),
            )
            if (candidates.isEmpty()) {
                if (_status.value !is LinkStatus.PairingRequired) {
                    _status.value = LinkStatus.WaitingForWirelessDebugging
                }
                waitForRetry(500L)
                continue
            }
            if (!connecting.compareAndSet(false, true)) {
                delay(200L)
                continue
            }
            var pairingRequired = false
            var lastError: Throwable? = null
            var connected = false
            try {
                for (candidate in candidates) {
                    _status.value = LinkStatus.Connecting(candidate.host, candidate.port)
                    try {
                        client.connect(candidate.host, candidate.port)
                        connected = true
                        rememberPort(candidate.port)
                        Log.i(TAG, "connected via $candidate")
                        break
                    } catch (error: Throwable) {
                        lastError = error
                        Log.w(TAG, "connect to $candidate failed: ${safeMessage(error)}")
                        if (isPairingFailure(error)) {
                            // adbd is reachable but rejects our key: trying other
                            // addresses cannot help, the user has to pair.
                            pairingRequired = true
                            break
                        }
                        if (candidate.origin == AdbEndpoint.Origin.REMEMBERED && isRefused(error)) {
                            forgetPort()
                        }
                    }
                }
            } finally {
                connecting.set(false)
            }
            if (connected) {
                attempt = 0
                onConnected()
                continue
            }
            attempt++
            val wait = if (pairingRequired) PAIRING_RETRY_MS else backoffMs(attempt)
            _status.value = if (pairingRequired) {
                LinkStatus.PairingRequired
            } else {
                LinkStatus.Retrying(lastError?.let(::safeMessage) ?: "unknown error", wait)
            }
            waitForRetry(wait)
        }
    }

    private suspend fun waitForRetry(ms: Long) {
        val kick = retryKick.get()
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < end) {
            if (retryKick.get() != kick) return
            delay(RETRY_POLL_MS)
        }
    }

    private fun onConnected() {
        PairingNotifier.dismissPrompt(app)
        scope.launch {
            val output = client.shell("cmd device_state print-states").getOrNull().orEmpty()
            val catalog = DeviceStateCatalog.parse(output)
            _deviceStates.value = catalog
            if (catalog.isEmpty) {
                Log.w(TAG, "device_state catalog unavailable; using Fold7 defaults: ${output.take(200)}")
            } else {
                Log.i(TAG, "device states: $catalog; concurrent supported=${catalog.supportsConcurrentDisplays}")
            }
        }
    }

    private fun onClientEvent(event: EmbeddedAdbAngleClient.Event) {
        when (event) {
            is EmbeddedAdbAngleClient.Event.Connected -> {
                _status.value = LinkStatus.Connected(event.host, event.port, angleLive = false, captureLive = false)
            }

            is EmbeddedAdbAngleClient.Event.Streams -> {
                val current = _status.value as? LinkStatus.Connected
                if (current != null) {
                    _status.value = current.copy(angleLive = event.angleLive, captureLive = event.captureLive)
                }
                setPrivateAngleLive(event.angleLive)
            }

            is EmbeddedAdbAngleClient.Event.Lost -> {
                setPrivateAngleLive(false)
                _status.value = LinkStatus.Disconnected(event.reason)
                kickRetry()
            }
        }
    }

    private fun setPrivateAngleLive(live: Boolean) {
        if (privateAngleLive == live) return
        privateAngleLive = live
        if (live) {
            sensor.stop()
        } else {
            // Keep the angle flowing from the public sensor; the overlay does not
            // care where samples come from. Without a sensor the readout stays
            // at its last value rather than being wiped, so a link blip no
            // longer makes the angle "disappear".
            if (!startPublicSensor()) {
                Log.w(TAG, "no public hinge sensor; angle frozen until the ADB stream returns")
            }
        }
    }

    private fun startPublicSensor(): Boolean = sensor.start()

    suspend fun pair(code: String): Result<Unit> {
        val endpoint = _pairEndpoint.value ?: run {
            val error = IllegalStateException("pairing dialog not open")
            _status.value = LinkStatus.PairingFailed("pairing dialog not open")
            return Result.failure(error)
        }
        if (code.length != 6 || !code.all(Char::isDigit)) {
            _status.value = LinkStatus.PairingFailed("code must be 6 digits")
            return Result.failure(IllegalArgumentException("code must be 6 digits"))
        }
        if (!pairing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("pairing already in progress"))
        }
        return try {
            _status.value = LinkStatus.Pairing
            val hosts = listOf(AdbEndpointCandidates.LOOPBACK, endpoint.host).distinct()
            var failure: Throwable? = null
            for (host in hosts) {
                try {
                    Kadb.pair(host, endpoint.port, code, "ZFoldDuo")
                    failure = null
                    break
                } catch (error: Throwable) {
                    failure = error
                    Log.w(TAG, "pairing via $host:${endpoint.port} failed: ${safeMessage(error)}")
                }
            }
            failure?.let { throw it }
            _status.value = LinkStatus.Paired
            kickRetry()
            Result.success(Unit)
        } catch (error: Throwable) {
            _status.value = LinkStatus.PairingFailed(safeMessage(error))
            Result.failure(error)
        } finally {
            pairing.set(false)
        }
    }

    fun prepareCoverDisplay() {
        scope.launch {
            displayCommand.withLock {
                // Keep the inner panel as logical display 0 and expose the
                // cover as display 1. The outer-default state does the reverse and
                // visibly blanks/remaps the inner panel during a close.
                val state = _deviceStates.value.concurrentInnerDefault
                if (client.shell("cmd device_state state $state").isSuccess) {
                    client.shell("cmd display enable-display $CONCURRENT_COVER_DISPLAY_ID")
                    client.shell("cmd display power-reset $CONCURRENT_COVER_DISPLAY_ID")
                }
            }
        }
    }

    fun releasePreparedCoverDisplay() {
        scope.launch {
            displayCommand.withLock {
                client.shell("cmd device_state state reset")
            }
        }
    }

    /**
     * Puts the device back into its natural display state and drops any shell
     * wake locks left behind by an earlier session that lost its link mid
     * transition. Called by the overlay when a link comes up while it is idle.
     */
    fun recoverDisplayState() {
        scope.launch {
            displayCommand.withLock {
                if (!client.isConnected) return@withLock
                client.shell("cmd device_state state reset")
                val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                val leaked = heldDisplayWakeLocks(status)
                if (leaked.isNotEmpty()) {
                    Log.w(TAG, "releasing leaked display wake locks: $leaked")
                    leaked.forEach { releaseDisplayWakeLockLocked(it) }
                }
            }
        }
    }

    fun setDisplayWakeLock(displayId: Int, held: Boolean) {
        if (displayId < 0) return
        scope.launch {
            displayCommand.withLock {
                if (held) {
                    val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                    if (!displayWakeLockHeld(status, displayId)) {
                        client.shell("cmd power set-wakelock acquire -d $displayId FULL_WAKE_LOCK")
                    }
                } else {
                    releaseDisplayWakeLockLocked(displayId)
                }
            }
        }
    }

    /** Drains the reference count completely so it cannot leak across open/close cycles. */
    private fun releaseDisplayWakeLockLocked(displayId: Int) {
        repeat(MAX_WAKE_LOCK_RELEASES) {
            val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
            if (!displayWakeLockHeld(status, displayId)) return
            client.shell("cmd power set-wakelock release -d $displayId FULL_WAKE_LOCK")
        }
    }

    fun isPreparedCoverDisplayPhysicallyClosed(onResult: (Boolean) -> Unit) {
        scope.launch {
            val closed = displayCommand.withLock {
                val dump = client.shell("dumpsys device_state").getOrNull().orEmpty()
                val base = DeviceStateCatalog.baseState(dump)
                base != null && (base.first == _deviceStates.value.closed || base.second == "CLOSED")
            }
            withContext(Dispatchers.Main.immediate) { onResult(closed) }
        }
    }

    /**
     * The inner-default state keeps the inner panel as display 0 and the cover
     * as display 1. Resetting it directly at CLOSED makes Samsung power-cycle
     * the cover. Promote the already-lit cover to display 0 through the
     * outer-default state first; the final reset then only removes the hidden
     * inner display.
     */
    fun finishClosedDisplayHandoff(onComplete: () -> Unit) {
        scope.launch {
            displayCommand.withLock {
                val outer = _deviceStates.value.concurrentOuterDefault
                client.shell("cmd device_state state $outer")
                for (attempt in 0 until COVER_PROMOTION_POLL_COUNT) {
                    val dump = client.shell("dumpsys device_state").getOrNull().orEmpty()
                    val committed = DeviceStateCatalog.committedState(dump)
                    if (committed != null && committed.first == outer) break
                    delay(COVER_PROMOTION_POLL_MS)
                }
                client.shell("cmd device_state state reset")
            }
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    private fun readPropertyPort(): Int? {
        val now = System.nanoTime()
        propertyPortCache?.let { (at, port) ->
            if (now - at < TimeUnit.SECONDS.toNanos(PROPERTY_CACHE_S)) return port
        }
        val port = runCatching {
            val process = ProcessBuilder("getprop", "service.adb.tls.port")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(1, TimeUnit.SECONDS)
            val output = if (finished) process.inputStream.bufferedReader().readText() else null
            if (!finished) process.destroy()
            AdbEndpointCandidates.parsePropertyPort(output)
        }.getOrNull()
        propertyPortCache = now to port
        return port
    }

    private fun rememberedPort(): Int? = prefs.getInt(KEY_LAST_PORT, 0).takeIf { it in 1..65535 }
    private fun rememberPort(port: Int) = prefs.edit().putInt(KEY_LAST_PORT, port).apply()
    private fun forgetPort() = prefs.edit().remove(KEY_LAST_PORT).apply()

    private fun backoffMs(attempt: Int): Long {
        var wait = 1_000L
        repeat((attempt - 1).coerceIn(0, 8)) { wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS) }
        return wait
    }

    private fun isPairingFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AdbPairAuthException || it.message.orEmpty().contains("TLS handshake", ignoreCase = true)
        }

    private fun isRefused(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is ConnectException }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun displayWakeLockHeld(status: String, displayId: Int): Boolean =
        ShellWakeLocks.isHeld(status, displayId)

    private fun heldDisplayWakeLocks(status: String): Set<Int> = ShellWakeLocks.heldDisplays(status)

    private const val TAG = "ZFoldDuoEngine"
    private const val KEY_LAST_PORT = "last_port"
    private const val MAX_WAKE_LOCK_RELEASES = 32
    private const val COVER_PROMOTION_POLL_COUNT = 20
    private const val COVER_PROMOTION_POLL_MS = 16L
    private const val CONCURRENT_COVER_DISPLAY_ID = 1
    private const val PAIRING_RETRY_MS = 3_000L
    private const val MAX_BACKOFF_MS = 10_000L
    private const val RETRY_POLL_MS = 250L
    private const val PROPERTY_CACHE_S = 2L
}

/** Keeps the Kadb key-store wiring in one place so tests can avoid Android paths. */
private object KadbCertSetup {
    fun configure(filesDir: File) {
        com.flyfishxu.kadb.cert.KadbCert.configure(
            com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore(filesDir.resolve("adb_private_key.pem").path.toPath()),
        )
    }
}
