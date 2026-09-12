package com.foldduo.hinge.effect

import android.view.Display
import kotlin.math.max
import kotlin.math.min

private const val INNER_MAX_ASPECT = 1.45f

fun Display?.isInnerPanel(): Boolean {
    val mode = runCatching { this?.mode }.getOrNull() ?: return false
    val a = mode.physicalWidth.toFloat()
    val b = mode.physicalHeight.toFloat()
    if (a <= 0f || b <= 0f) return false
    return max(a, b) / min(a, b) < INNER_MAX_ASPECT
}
