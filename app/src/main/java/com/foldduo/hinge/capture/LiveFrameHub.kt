package com.foldduo.hinge.capture

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/** Zero-copy hand-off between the shell capture process and the overlay renderer. */
object LiveFrameHub {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestedMask = AtomicInteger(0)
    private val lock = Any()
    private val pending = arrayOfNulls<Bitmap>(MAX_DISPLAYS)
    private val dispatchScheduled = BooleanArray(MAX_DISPLAYS)

    @Volatile
    private var listener: ((displayId: Int, bitmap: Bitmap, timestampNanos: Long) -> Unit)? = null
    private val timestamps = LongArray(MAX_DISPLAYS)

    val sink = object : ILiveFrameSink.Stub() {
        override fun getRequestedDisplayMask(): Int = requestedMask.get()

        override fun onFrame(displayId: Int, buffer: HardwareBuffer, timestampNanos: Long) {
            if (displayId !in 0 until MAX_DISPLAYS || requestedMask.get() and (1 shl displayId) == 0) {
                buffer.close()
                return
            }
            val bitmap = try {
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            } finally {
                buffer.close()
            } ?: return

            var schedule = false
            synchronized(lock) {
                pending[displayId]?.recycle()
                pending[displayId] = bitmap
                timestamps[displayId] = timestampNanos
                if (!dispatchScheduled[displayId]) {
                    dispatchScheduled[displayId] = true
                    schedule = true
                }
            }
            if (schedule) mainHandler.post { dispatch(displayId) }
        }
    }

    fun setListener(value: ((displayId: Int, bitmap: Bitmap, timestampNanos: Long) -> Unit)?) {
        listener = value
        if (value == null) clearPending()
    }

    fun requestDisplays(displayIds: Collection<Int>) {
        var mask = 0
        displayIds.forEach { id -> if (id in 0 until MAX_DISPLAYS) mask = mask or (1 shl id) }
        requestedMask.set(mask)
        synchronized(lock) {
            for (id in 0 until MAX_DISPLAYS) {
                if (mask and (1 shl id) == 0) {
                    pending[id]?.recycle()
                    pending[id] = null
                }
            }
        }
    }

    private fun dispatch(displayId: Int) {
        val frame: Bitmap
        val timestamp: Long
        synchronized(lock) {
            frame = pending[displayId] ?: run {
                dispatchScheduled[displayId] = false
                return
            }
            pending[displayId] = null
            timestamp = timestamps[displayId]
            dispatchScheduled[displayId] = false
        }
        listener?.invoke(displayId, frame, timestamp) ?: frame.recycle()
    }

    private fun clearPending() = synchronized(lock) {
        requestedMask.set(0)
        for (id in 0 until MAX_DISPLAYS) {
            pending[id]?.recycle()
            pending[id] = null
        }
    }

    private const val MAX_DISPLAYS = 8
}
