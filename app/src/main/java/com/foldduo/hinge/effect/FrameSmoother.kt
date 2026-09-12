package com.foldduo.hinge.effect

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp

class FrameSmoother(private val onFrame: (Float) -> Unit) : Choreographer.FrameCallback {
    var target = 0f
        private set
    var current = 0f
        private set
    var tauS = DEFAULT_TAU_S
    private var scheduled = false
    private var lastFrameNanos = 0L

    fun setTarget(tilt: Float) {
        target = tilt
        if (current != target) schedule()
    }

    fun snap(tilt: Float) {
        target = tilt
        current = tilt
        cancel()
    }

    fun cancel() {
        if (!scheduled) return
        scheduled = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos
        current += (target - current) * (1f - exp(-dt / tauS))
        if (abs(target - current) < 0.02f) current = target
        onFrame(current)
        if (current != target) schedule() else lastFrameNanos = 0L
    }

    companion object {
        const val DEFAULT_TAU_S = 0.045f
    }
}
