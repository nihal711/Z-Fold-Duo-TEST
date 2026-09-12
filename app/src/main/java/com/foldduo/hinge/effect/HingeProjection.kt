package com.foldduo.hinge.effect

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log
import com.foldduo.hinge.R

data class ProjectionAxis(
    val splitsX: Boolean,
    val position: Float,
    val eyePos: Float = position,
    val movingSide: Int? = null,
)

/** Pinhole projection and depth-dependent frost for a rotating display plane. */
object HingeProjection {
    /** Avoid the singular exactly edge-on pose while preserving physical motion. */
    const val MAX_TILT = 89.5f
    const val FLAT_EPSILON = 0.05f
    const val FLAT_HINGE = 172f
    const val CLOSED_HINGE = 3f
    private const val REFERENCE_PX_PER_MM = 6f

    @Volatile
    private var source: String? = null

    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.spatial_projection)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (error: Exception) {
            Log.e("ZFoldDuoProjection", "AGSL compile failed", error)
            null
        }
    }

    /**
     * The right inner leaf is the fixed world plane. The left leaf carrying
     * the cover display rotates through the full remaining dihedral angle.
     */
    fun tiltForHinge(hingeDegrees: Float, config: EffectProfile): Float =
        ((PHYSICAL_FLAT_HINGE - hingeDegrees.coerceIn(0f, PHYSICAL_FLAT_HINGE)) *
            config.intensity).coerceIn(0f, MAX_TILT)

    /** The cover is the reverse face of that rotating left leaf. */
    fun coverTiltForHinge(hingeDegrees: Float, config: EffectProfile): Float =
        (hingeDegrees.coerceIn(0f, PHYSICAL_FLAT_HINGE) * config.intensity)
            .coerceIn(0f, MAX_TILT)

    fun tiltFor(hingeDegrees: Float, config: EffectProfile, innerPanel: Boolean): Float =
        if (hingeDegrees.isNaN()) 0f
        else if (innerPanel) tiltForHinge(hingeDegrees, config)
        else coverTiltForHinge(hingeDegrees, config)

    fun centeredFold(width: Float, height: Float, foldSplitsLong: Boolean): ProjectionAxis {
        val splitsX = if (foldSplitsLong) width >= height else width < height
        return ProjectionAxis(splitsX, (if (splitsX) width else height) * 0.5f)
    }

    fun coverFold(width: Float, config: EffectProfile): ProjectionAxis =
        if (config.coverFrostFromRight) {
            ProjectionAxis(splitsX = true, position = 0f, eyePos = width * 0.5f, movingSide = 1)
        } else {
            ProjectionAxis(splitsX = true, position = width, eyePos = width * 0.5f, movingSide = -1)
        }

    fun foldFor(innerPanel: Boolean, width: Float, height: Float, config: EffectProfile): ProjectionAxis =
        if (innerPanel) centeredFold(width, height, config.foldSplitsLong) else coverFold(width, config)

    fun pxPerMm(context: Context): Float {
        val xdpi = context.resources.displayMetrics.xdpi
        return if (xdpi.isFinite() && xdpi > 0f) xdpi / 25.4f else REFERENCE_PX_PER_MM
    }

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        tiltDegrees: Float,
        config: EffectProfile,
        pxPerMm: Float,
        fold: ProjectionAxis,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("tiltDegrees", tiltDegrees)
        shader.setFloatUniform("eyeDistancePx", config.eyeDistanceMm * pxPerMm)
        shader.setFloatUniform("hingePos", fold.position)
        shader.setFloatUniform("eyeX", fold.eyePos)
        shader.setFloatUniform("axisSwap", if (fold.splitsX) 0f else 1f)
        shader.setFloatUniform("paneSide", (fold.movingSide ?: config.movingSide).toFloat())
        shader.setFloatUniform("blurSpread", config.blurSpread)
    }

    private const val PHYSICAL_FLAT_HINGE = 180f
}
