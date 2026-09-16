package com.foldduo.hinge.capture

import android.content.AttributionSource
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.SystemClock
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.ObjIntConsumer

/**
 * Runs as UID 2000 through app_process. SurfaceFlinger composes a half-resolution
 * HardwareBuffer for each requested logical display and Binder passes it to the APK.
 *
 * Everything written to stdout is relayed into the app's logcat by the ADB
 * client, so failures here become visible in `adb logcat -s ZFoldDuoEngine`.
 * A single failed frame must never take the whole bridge down: the app
 * restarts the bridge when it exits, but every restart costs a visible gap in
 * live frames, and on the Fold8 Ultra those gaps were being reported as the
 * link "disconnecting".
 */
object LiveCaptureBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        log("starting on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val sink = try {
            connectSink()
        } catch (error: Throwable) {
            fail("frame sink unavailable", error)
            return
        }
        val capture = try {
            DisplayCapture()
        } catch (error: Throwable) {
            fail("display capture unavailable", error)
            return
        }
        log("$READY_MARKER via ${capture.description}")

        val failures = IntArray(MAX_DISPLAYS)
        try {
            while (true) {
                val frameStart = SystemClock.uptimeMillis()
                val mask = try {
                    sink.requestedDisplayMask
                } catch (error: DeadObjectException) {
                    throw error
                } catch (error: Throwable) {
                    logThrottled("mask", "could not read requested displays", error)
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                if (mask == 0) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                for (displayId in 0 until MAX_DISPLAYS) {
                    if (mask and (1 shl displayId) == 0) continue
                    val buffer = try {
                        capture.capture(displayId)
                    } catch (error: Throwable) {
                        failures[displayId]++
                        logThrottled("capture$displayId", "capture of display $displayId failed (${failures[displayId]}x)", error)
                        null
                    } ?: continue
                    failures[displayId] = 0
                    try {
                        sink.onFrame(displayId, buffer, SystemClock.elapsedRealtimeNanos())
                    } catch (error: DeadObjectException) {
                        throw error
                    } catch (error: Throwable) {
                        logThrottled("deliver$displayId", "frame delivery for display $displayId failed", error)
                    } finally {
                        buffer.close()
                    }
                }
                val wait = FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - frameStart)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: DeadObjectException) {
            log("app process was replaced or stopped; exiting")
        } catch (_: InterruptedException) {
            log("interrupted; exiting")
        }
    }

    private fun connectSink(): ILiveFrameSink {
        val activityManagerClass = Class.forName("android.app.ActivityManager")
        val activityManager = activityManagerClass.getDeclaredMethod("getService").invoke(null)
        val activityManagerInterface = Class.forName("android.app.IActivityManager")
        val token = Binder()
        val holder = activityManagerInterface.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            android.os.IBinder::class.java,
            String::class.java,
        ).invoke(activityManager, LiveFrameProvider.AUTHORITY, 0, token, "ZFoldDuoCapture")
            ?: error("frame provider unavailable (is ZFoldDuo installed and its provider enabled?)")
        val provider = Class.forName("android.app.ContentProviderHolder")
            .getField("provider").get(holder)
        val providerInterface = Class.forName("android.content.IContentProvider")
        val attributionBuilder = AttributionSource.Builder(SHELL_UID)
            .setPackageName("com.android.shell")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            attributionBuilder.setPid(android.os.Process.myPid())
        }
        val attribution = attributionBuilder.build()
        val result = providerInterface.getMethod(
            "call",
            AttributionSource::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Bundle::class.java,
        ).invoke(
            provider,
            attribution,
            LiveFrameProvider.AUTHORITY,
            LiveFrameProvider.METHOD_CONNECT,
            null,
            null,
        ) as? Bundle
            ?: error("frame provider call failed")
        return ILiveFrameSink.Stub.asInterface(result.getBinder(LiveFrameProvider.KEY_SINK))
            ?: error("frame sink unavailable")
    }

    /**
     * Wraps `IWindowManager.captureDisplay`. The nested capture classes moved
     * between `android.window.ScreenCapture` and `ScreenCaptureInternal` across
     * Android 16 point releases, so both names are tried.
     */
    private class DisplayCapture {
        private val listenerConstructor: java.lang.reflect.Constructor<*>
        private val getHardwareBuffer: java.lang.reflect.Method
        private val windowManager: Any
        private val captureDisplay: java.lang.reflect.Method
        private val captureArgs: Any
        val description: String

        init {
            val global = Class.forName("android.view.WindowManagerGlobal")
            windowManager = global.getDeclaredMethod("getWindowManagerService").invoke(null)
                ?: error("window manager unavailable")
            val errors = mutableListOf<String>()
            var resolved: Resolved? = null
            for (outer in CAPTURE_CLASS_CANDIDATES) {
                try {
                    resolved = resolve(outer)
                    break
                } catch (error: Throwable) {
                    errors += "$outer: ${error.javaClass.simpleName}: ${error.message}"
                }
            }
            val ok = resolved ?: error("no usable screen-capture API; tried ${errors.joinToString("; ")}")
            listenerConstructor = ok.listenerConstructor
            getHardwareBuffer = ok.getHardwareBuffer
            captureDisplay = ok.captureDisplay
            captureArgs = ok.captureArgs
            description = ok.outerClass
        }

        private class Resolved(
            val outerClass: String,
            val listenerConstructor: java.lang.reflect.Constructor<*>,
            val getHardwareBuffer: java.lang.reflect.Method,
            val captureDisplay: java.lang.reflect.Method,
            val captureArgs: Any,
        )

        private fun resolve(outer: String): Resolved {
            val argsClass = Class.forName("$outer\$CaptureArgs")
            val listenerClass = Class.forName("$outer\$ScreenCaptureListener")
            val screenshotClass = Class.forName("$outer\$ScreenshotHardwareBuffer")
            val listenerConstructor = listenerClass.getDeclaredConstructor(ObjIntConsumer::class.java)
            val getHardwareBuffer = screenshotClass.getDeclaredMethod("getHardwareBuffer")
            val captureDisplay = windowManager.javaClass.getMethod(
                "captureDisplay",
                Int::class.javaPrimitiveType,
                argsClass,
                listenerClass,
            )
            val builderClass = Class.forName("$outer\$CaptureArgs\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getDeclaredMethod("setFrameScale", Float::class.javaPrimitiveType)
                .invoke(builder, FRAME_SCALE)
            val captureArgs = builderClass.getDeclaredMethod("build").invoke(builder)
                ?: error("capture args unavailable")
            return Resolved(outer, listenerConstructor, getHardwareBuffer, captureDisplay, captureArgs)
        }

        fun capture(displayId: Int): HardwareBuffer? {
            val latch = CountDownLatch(1)
            var result: Any? = null
            var status = -1
            val consumer = ObjIntConsumer<Any?> { buffer, code ->
                result = buffer
                status = code
                latch.countDown()
            }
            val listener = listenerConstructor.newInstance(consumer)
            captureDisplay.invoke(windowManager, displayId, captureArgs, listener)
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || status != 0) return null
            return result?.let { getHardwareBuffer.invoke(it) as? HardwareBuffer }
        }
    }

    private val lastLogAt = HashMap<String, Long>()

    private fun logThrottled(key: String, message: String, error: Throwable) {
        val now = SystemClock.uptimeMillis()
        val last = lastLogAt[key] ?: 0L
        if (now - last < ERROR_LOG_INTERVAL_MS) return
        lastLogAt[key] = now
        log("$message: ${describe(error)}")
    }

    private fun fail(message: String, error: Throwable) {
        log("$ERROR_MARKER: $message: ${describe(error)}")
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        trace.toString().lineSequence().take(MAX_TRACE_LINES).forEach(::log)
        System.out.flush()
        System.exit(1)
    }

    private fun describe(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val head = "${error.javaClass.simpleName}: ${error.message ?: ""}".trim()
        return if (root !== error) "$head (caused by ${root.javaClass.simpleName}: ${root.message ?: ""})" else head
    }

    private fun log(message: String) {
        System.out.println(message)
        System.out.flush()
    }

    private const val MAX_DISPLAYS = 8
    private const val SHELL_UID = 2000
    private const val FRAME_SCALE = 0.5f
    private const val FRAME_INTERVAL_MS = 33L
    private const val IDLE_POLL_MS = 50L
    private const val CAPTURE_TIMEOUT_MS = 100L
    private const val ERROR_LOG_INTERVAL_MS = 5_000L
    private const val MAX_TRACE_LINES = 12
    private const val READY_MARKER = "ZFoldDuo live capture ready"
    private const val ERROR_MARKER = "ZFoldDuo live capture error"
    private val CAPTURE_CLASS_CANDIDATES = listOf(
        "android.window.ScreenCaptureInternal",
        "android.window.ScreenCapture",
    )
}
