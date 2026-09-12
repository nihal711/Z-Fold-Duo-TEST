package com.foldduo.hinge.capture

import android.content.AttributionSource
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.ObjIntConsumer

/**
 * Runs as UID 2000 through app_process. SurfaceFlinger composes a half-resolution
 * HardwareBuffer for each requested logical display and Binder passes it to the APK.
 */
object LiveCaptureBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        val sink = connectSink()
        val capture = DisplayCapture()
        System.out.println("ZFoldDuo live capture ready")

        try {
            while (true) {
                val frameStart = SystemClock.uptimeMillis()
                val mask = sink.requestedDisplayMask
                if (mask == 0) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                for (displayId in 0 until MAX_DISPLAYS) {
                    if (mask and (1 shl displayId) == 0) continue
                    val buffer = capture.capture(displayId) ?: continue
                    try {
                        sink.onFrame(displayId, buffer, SystemClock.elapsedRealtimeNanos())
                    } finally {
                        buffer.close()
                    }
                }
                val wait = FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - frameStart)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: DeadObjectException) { /* APK process was replaced or stopped. */ }
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
            ?: error("frame provider unavailable")
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

    private class DisplayCapture {
        private val argsClass = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs")
        private val listenerClass = Class.forName("android.window.ScreenCaptureInternal\$ScreenCaptureListener")
        private val screenshotClass = Class.forName("android.window.ScreenCaptureInternal\$ScreenshotHardwareBuffer")
        private val listenerConstructor = listenerClass.getDeclaredConstructor(ObjIntConsumer::class.java)
        private val getHardwareBuffer = screenshotClass.getDeclaredMethod("getHardwareBuffer")
        private val windowManager: Any
        private val captureDisplay: java.lang.reflect.Method
        private val captureArgs: Any

        init {
            val global = Class.forName("android.view.WindowManagerGlobal")
            windowManager = global.getDeclaredMethod("getWindowManagerService").invoke(null)
                ?: error("window manager unavailable")
            captureDisplay = windowManager.javaClass.getMethod(
                "captureDisplay",
                Int::class.javaPrimitiveType,
                argsClass,
                listenerClass,
            )
            val builderClass = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getDeclaredMethod("setFrameScale", Float::class.javaPrimitiveType)
                .invoke(builder, FRAME_SCALE)
            captureArgs = builderClass.getDeclaredMethod("build").invoke(builder)
                ?: error("capture args unavailable")
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

    private const val MAX_DISPLAYS = 8
    private const val SHELL_UID = 2000
    private const val FRAME_SCALE = 0.5f
    private const val FRAME_INTERVAL_MS = 33L
    private const val IDLE_POLL_MS = 50L
    private const val CAPTURE_TIMEOUT_MS = 100L
}
