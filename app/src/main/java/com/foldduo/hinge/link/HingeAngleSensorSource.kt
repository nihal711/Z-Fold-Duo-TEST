package com.foldduo.hinge.link

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Public `TYPE_HINGE_ANGLE` fallback. It is coarser than Samsung's private
 * sensor and needs no ADB, so the angle display and the basic single-panel
 * animation keep working while the wireless-debugging link is down.
 */
class HingeAngleSensorSource(
    context: Context,
    private val onAngle: (angle: Float, timestampNanos: Long) -> Unit,
) {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private var thread: HandlerThread? = null

    @Volatile
    var isRunning = false
        private set

    val isAvailable: Boolean get() = sensor != null

    val description: String
        get() = sensor?.let { "${it.name} (${it.vendor}, resolution ${it.resolution}°)" } ?: "unavailable"

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val value = event.values.firstOrNull() ?: return
            if (!value.isFinite()) return
            onAngle(value.coerceIn(0f, 180f), event.timestamp)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val manager = sensorManager ?: return false
        val hinge = sensor ?: return false
        val worker = HandlerThread("ZFoldDuo-hinge-sensor").also { it.start() }
        val registered = manager.registerListener(
            listener,
            hinge,
            SensorManager.SENSOR_DELAY_FASTEST,
            Handler(worker.looper),
        )
        if (!registered) {
            worker.quitSafely()
            Log.w(TAG, "hinge sensor registration refused")
            return false
        }
        thread = worker
        isRunning = true
        Log.i(TAG, "public hinge sensor active: $description")
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "public hinge sensor stopped")
    }

    private companion object {
        const val TAG = "ZFoldDuoEngine"
    }
}
