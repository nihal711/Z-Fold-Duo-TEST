package com.foldduo.hinge

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
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
import java.util.concurrent.atomic.AtomicBoolean

data class AngleSample(
    val angle: Float,
    val timestampNanos: Long,
)

/** One process-wide ADB connection shared by the activity and accessibility overlay. */
object AngleRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private val displayCommand = Mutex()
    private val _status = MutableStateFlow("ワイヤレスデバッグを待っています")
    private val _sample = MutableStateFlow<AngleSample?>(null)
    private val _pairEndpoint = MutableStateFlow<MdnsEndpoint?>(null)
    private val _connectEndpoint = MutableStateFlow<MdnsEndpoint?>(null)

    val status = _status.asStateFlow()
    val sample = _sample.asStateFlow()
    val pairEndpoint = _pairEndpoint.asStateFlow()
    val connectEndpoint = _connectEndpoint.asStateFlow()

    private var initialized = false
    private lateinit var mdns: KadbMdnsAndroid
    private lateinit var client: EmbeddedAdbAngleClient

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        KadbCert.configure(OkioFilePrivateKeyStore(app.filesDir.resolve("adb_private_key.pem").path.toPath()))
        client = EmbeddedAdbAngleClient(
            onStatus = {
                _status.value = it
                if (it.contains("切断") || it.contains("停止")) _sample.value = null
            },
            onAngle = { angle, timestamp ->
                _sample.value = AngleSample(angle, timestamp)
            },
        )
        mdns = KadbMdnsAndroid(
            app,
            MdnsConfig(setOf(MdnsServiceType.TLS_CONNECT, MdnsServiceType.TLS_PAIRING)),
        )
        scope.launch {
            mdns.state.collect { state ->
                _pairEndpoint.value = state.pairDevices.firstOrNull()
                _connectEndpoint.value = state.connectDevices.firstOrNull()
            }
        }
        mdns.start()
        scope.launch {
            while (isActive) {
                val endpoint = _connectEndpoint.value
                if (endpoint != null && !client.isConnected && connecting.compareAndSet(false, true)) {
                    try {
                        _sample.value = null
                        client.connect(endpoint.host, endpoint.port)
                    } catch (_: Throwable) {
                        _status.value = "ADBペアリングが必要です"
                    } finally {
                        connecting.set(false)
                    }
                }
                delay(if (endpoint == null) 500L else 2_500L)
            }
        }
    }

    suspend fun pair(code: String): Result<Unit> {
        val endpoint = _pairEndpoint.value ?: run {
            val error = IllegalStateException("ペア設定コード画面を開いたままにしてください")
            _status.value = error.message.orEmpty()
            return Result.failure(error)
        }
        if (code.length != 6 || !code.all(Char::isDigit)) {
            val error = IllegalArgumentException("6桁のコードを入力してください")
            _status.value = error.message.orEmpty()
            return Result.failure(error)
        }
        if (!pairing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("ペアリング中です"))
        }
        return try {
            _status.value = "ペアリング中"
            Kadb.pair(endpoint.host, endpoint.port, code, "ZFoldDuo")
            _status.value = "ペアリング完了・接続中"
            Result.success(Unit)
        } catch (error: Throwable) {
            _status.value = "ペアリング失敗: ${safeMessage(error)}"
            Result.failure(error)
        } finally {
            pairing.set(false)
        }
    }

    fun prepareCoverDisplay() {
        scope.launch {
            displayCommand.withLock {
                // Keep the inner panel as logical display 0 and expose the
                // cover as display 1. State 5 does the reverse and visibly
                // blanks/remaps the inner panel during a close.
                if (client.shell("cmd device_state state 4").isSuccess) {
                    client.shell("cmd display enable-display 1")
                    client.shell("cmd display power-reset 1")
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

    fun setDisplayWakeLock(displayId: Int, held: Boolean) {
        if (displayId < 0) return
        scope.launch {
            displayCommand.withLock {
                val operation = if (held) "acquire" else "release"
                if (held) {
                    val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                    if (!displayWakeLockHeld(status, displayId)) {
                        client.shell("cmd power set-wakelock $operation -d $displayId FULL_WAKE_LOCK")
                    }
                } else {
                    // Older builds acquired the same shell lock repeatedly.
                    // Drain its reference count completely so it cannot leak
                    // across several open/close cycles.
                    repeat(MAX_WAKE_LOCK_RELEASES) {
                        val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                        if (!displayWakeLockHeld(status, displayId)) return@withLock
                        client.shell("cmd power set-wakelock $operation -d $displayId FULL_WAKE_LOCK")
                    }
                }
            }
        }
    }

    fun isPreparedCoverDisplayPhysicallyClosed(onResult: (Boolean) -> Unit) {
        scope.launch {
            val closed = displayCommand.withLock {
                val state = client.shell("dumpsys device_state").getOrNull().orEmpty()
                state.contains(
                    "mBaseState=Optional[DeviceState{identifier=0, name='CLOSED'",
                )
            }
            withContext(Dispatchers.Main.immediate) { onResult(closed) }
        }
    }

    /**
     * State 4 keeps the inner panel as display 0 and the cover as display 1.
     * Resetting it directly at CLOSED makes Samsung power-cycle the cover.
     * Promote the already-lit cover to display 0 through state 5 first; the
     * final reset then only removes the hidden inner display.
     */
    fun finishClosedDisplayHandoff(onComplete: () -> Unit) {
        scope.launch {
            displayCommand.withLock {
                client.shell("cmd device_state state 5")
                for (attempt in 0 until COVER_PROMOTION_POLL_COUNT) {
                    val state = client.shell("dumpsys device_state").getOrNull().orEmpty()
                    if (state.contains(
                            "mCommittedState=Optional[DeviceState{identifier=5, " +
                                "name='CONCURRENT_OUTER_DEFAULT'",
                        )
                    ) break
                    delay(COVER_PROMOTION_POLL_MS)
                }
                client.shell("cmd device_state state reset")
            }
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun displayWakeLockHeld(status: String, displayId: Int): Boolean {
        val lock = "Display $displayId, wakelock type: FULL_WAKE_LOCK:"
        return status.lineSequence().any { it.contains(lock) && it.contains("held=true") }
    }

    private const val MAX_WAKE_LOCK_RELEASES = 32
    private const val COVER_PROMOTION_POLL_COUNT = 20
    private const val COVER_PROMOTION_POLL_MS = 16L
}
