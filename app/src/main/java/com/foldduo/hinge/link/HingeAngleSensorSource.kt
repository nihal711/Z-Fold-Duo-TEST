package com.foldduo.hinge.link

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.Locale

/**
 * Hinge angle straight from SensorManager, used whenever the private ADB
 * stream is down.
 *
 * Samsung quantises the public `TYPE_HINGE_ANGLE` sensor to 90° on the Fold8
 * Ultra (the device itself reports `resolution 90.0°`), which is useless for
 * an animation. Samsung also registers vendor sensors (type >= 65536) whose
 * names mention the hinge or fold; some builds expose them to ordinary apps.
 * Every plausible candidate is registered and the finest one that actually
 * delivers events wins, so a usable vendor sensor is preferred automatically
 * while the public sensor remains the last resort.
 */
class HingeAngleSensorSource(
    context: Context,
    private val onAngle: (angle: Float, timestampNanos: Long) -> Unit,
) {
    class Candidate(val sensor: Sensor) {
        @Volatile var events = 0L
        @Volatile var lastValue = Float.NaN
        @Volatile var lastTimestampNanos = 0L
        @Volatile var registered = false
        @Volatile var implausible = false

        val isPublic: Boolean get() = sensor.type == Sensor.TYPE_HINGE_ANGLE
        val resolution: Float get() = sensor.resolution.takeIf { it > 0f && it.isFinite() } ?: DEFAULT_RESOLUTION

        fun describe(): String = String.format(
            Locale.US,
            "%s | type %d%s | %s | res %.3f | range %.1f | %s | events %d%s%s",
            sensor.name.trim(),
            sensor.type,
            sensor.stringType?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "",
            sensor.vendor?.trim() ?: "?",
            sensor.resolution,
            sensor.maximumRange,
            if (sensor.isWakeUpSensor) "wakeup" else "non-wakeup",
            events,
            if (lastValue.isFinite()) String.format(Locale.US, " last %.3f", lastValue) else "",
            when {
                implausible -> " IMPLAUSIBLE"
                !registered -> " NOT REGISTERED"
                else -> ""
            },
        )
    }

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    val candidates: List<Candidate> = discover()
    private var thread: HandlerThread? = null
    private val listeners = HashMap<Candidate, SensorEventListener>()

    @Volatile
    private var active: Candidate? = null

    @Volatile
    var isRunning = false
        private set

    val isAvailable: Boolean get() = candidates.isNotEmpty()

    /** The candidate whose events are currently forwarded, if any. */
    val activeCandidate: Candidate? get() = active

    /** True when the best sensor we can get is too coarse to animate with. */
    val isTooCoarse: Boolean
        get() {
            val best = active ?: candidates.filter { !it.implausible }.minByOrNull { it.resolution } ?: return true
            return best.resolution >= COARSE_RESOLUTION
        }

    val description: String
        get() {
            val current = active
            if (candidates.isEmpty()) return "unavailable"
            val best = current ?: candidates.first()
            return String.format(
                Locale.US,
                "%s (%s, resolution %.1f°)%s",
                best.sensor.name.trim(),
                best.sensor.vendor?.trim() ?: "?",
                best.sensor.resolution,
                if (current == null) " [no events yet]" else if (current.isPublic) " [public]" else " [vendor]",
            )
        }

    fun describeAll(): String =
        if (candidates.isEmpty()) "none" else candidates.joinToString("\n") { "  " + it.describe() }

    private fun discover(): List<Candidate> {
        val manager = sensorManager ?: return emptyList()
        val all = runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val vendor = all.filter { sensor ->
            sensor.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE && mentionsHinge(sensor)
        }
        val public = all.filter { it.type == Sensor.TYPE_HINGE_ANGLE }
            .ifEmpty { listOfNotNull(manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)) }
        return (vendor + public)
            .distinctBy { "${it.type}/${it.name}/${it.isWakeUpSensor}" }
            .map(::Candidate)
    }

    private fun mentionsHinge(sensor: Sensor): Boolean {
        val haystack = "${sensor.name} ${sensor.stringType.orEmpty()}".lowercase(Locale.US)
        return KEYWORDS.any(haystack::contains)
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val manager = sensorManager ?: return false
        if (candidates.isEmpty()) return false
        val worker = HandlerThread("ZFoldDuo-hinge-sensor").also { it.start() }
        val handler = Handler(worker.looper)
        var any = false
        for (candidate in candidates) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = onEvent(candidate, event)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val ok = try {
                manager.registerListener(listener, candidate.sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            } catch (error: SecurityException) {
                Log.w(TAG, "sensor ${candidate.sensor.name} refused: ${error.message}")
                false
            }
            candidate.registered = ok
            if (ok) {
                listeners[candidate] = listener
                any = true
            }
        }
        if (!any) {
            worker.quitSafely()
            Log.w(TAG, "no hinge sensor could be registered")
            return false
        }
        thread = worker
        isRunning = true
        Log.i(TAG, "hinge sensors registered:\n${describeAll()}")
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        listeners.values.forEach { sensorManager?.unregisterListener(it) }
        listeners.clear()
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "hinge sensors stopped")
    }

    private fun onEvent(candidate: Candidate, event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        if (!value.isFinite()) return
        if (value < -PLAUSIBLE_SLACK || value > 180f + PLAUSIBLE_SLACK) {
            if (!candidate.implausible) {
                candidate.implausible = true
                Log.w(TAG, "sensor ${candidate.sensor.name} reports $value; not a hinge angle in degrees")
            }
            return
        }
        candidate.events++
        candidate.lastValue = value
        candidate.lastTimestampNanos = event.timestamp
        val chosen = chooseActive()
        if (chosen !== candidate) return
        onAngle(value.coerceIn(0f, 180f), event.timestamp)
    }

    /** Finest reporting sensor wins; vendor beats public on ties. Sticky once chosen unless a finer one reports. */
    private fun chooseActive(): Candidate? {
        val reporting = candidates.filter { it.events > 0 && !it.implausible }
        if (reporting.isEmpty()) return null
        val best = reporting.minWithOrNull(compareBy<Candidate> { it.resolution }.thenBy { it.isPublic }) ?: return null
        val current = active
        if (current == null || best.resolution < current.resolution) {
            if (current !== best) Log.i(TAG, "hinge angle source: ${best.describe()}")
            active = best
            return best
        }
        return current
    }

    private companion object {
        const val TAG = "ZFoldDuoEngine"
        const val DEFAULT_RESOLUTION = 1f
        const val COARSE_RESOLUTION = 45f
        const val PLAUSIBLE_SLACK = 5f
        val KEYWORDS = listOf("hinge", "fold", "angle")
    }
}
