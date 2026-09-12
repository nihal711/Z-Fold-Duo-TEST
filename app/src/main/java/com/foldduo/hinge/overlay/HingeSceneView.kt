package com.foldduo.hinge.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import com.foldduo.hinge.effect.HingeProjection
import com.foldduo.hinge.effect.EffectProfile
import com.foldduo.hinge.effect.ProjectionAxis
import com.foldduo.hinge.effect.EffectPreferences
import kotlin.math.ceil

/** Full-screen live frame stream rendered through the frosted-glass fold shader. */
class HingeSceneView(
    context: Context,
    snapshot: Bitmap,
    foldLine: ((Float, Float, EffectProfile) -> ProjectionAxis)? = null,
    private val renderScale: Float = 2f,
) : ViewGroup(context) {
    private val fold = FoldView(context, snapshot, renderScale, foldLine)
    private val flat = FlatView(context, snapshot)

    var snapshot: Bitmap = snapshot
        private set

    var tilt: Float
        get() = fold.tilt
        set(value) {
            fold.tilt = value
            val useFold = value >= HingeProjection.FLAT_EPSILON
            fold.visibility = if (useFold) VISIBLE else INVISIBLE
            flat.visibility = if (useFold) INVISIBLE else VISIBLE
        }

    var config: EffectProfile
        get() = fold.config
        set(value) { fold.config = value }

    /** Swaps only the shader input; the fold geometry and animation keep running. */
    fun updateSnapshot(bitmap: Bitmap): Bitmap {
        val previous = snapshot
        snapshot = bitmap
        flat.updateSnapshot(bitmap)
        fold.updateSnapshot(bitmap)
        return previous
    }

    /**
     * Releases renderer references. The bitmap itself must be left to Android's
     * reference tracking: RenderThread can still hold an already-submitted
     * BitmapShader after the View has been detached.
     */
    fun release() {
        flat.release()
        fold.release()
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(flat)
        addView(fold)
        fold.pivotX = 0f
        fold.pivotY = 0f
        fold.scaleX = renderScale
        fold.scaleY = renderScale
        fold.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        fold.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        flat.layout(0, 0, right - left, bottom - top)
        fold.layout(0, 0, fold.measuredWidth, fold.measuredHeight)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    private class FlatView(context: Context, snapshot: Bitmap) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var snapshot = snapshot
        private var shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val matrix = Matrix()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            updateMatrix(w, h)
        }

        fun updateSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            val previous = shader
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            updateMatrix(width, height)
            previous.releaseNativeInstance()
            invalidate()
        }

        fun release() {
            paint.shader = null
            shader.releaseNativeInstance()
        }

        private fun updateMatrix(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        }

        override fun onDraw(canvas: Canvas) = canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    }

    private class FoldView(
        context: Context,
        snapshot: Bitmap,
        renderScale: Float,
        private val foldLine: ((Float, Float, EffectProfile) -> ProjectionAxis)?,
    ) : View(context) {
        private val shader: RuntimeShader? = HingeProjection.create(context)
        private val pxPerMm = HingeProjection.pxPerMm(context) / renderScale
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var snapshot = snapshot
        private var image = BitmapShader(snapshot, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        private val matrix = Matrix()
        var config: EffectProfile = EffectPreferences.config.value
        var tilt = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            updateMatrix(w, h)
        }

        fun updateSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            val previous = image
            image = BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
            updateMatrix(width, height)
            shader?.setInputShader("content", image)
            previous.releaseNativeInstance()
            invalidate()
        }

        fun release() {
            paint.shader = null
            shader?.releaseNativeInstance()
            image.releaseNativeInstance()
        }

        private fun updateMatrix(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            image.setLocalMatrix(matrix)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            canvas.drawColor(Color.BLACK)
            val effect = shader
            if (effect == null) {
                paint.shader = image
            } else {
                val line = foldLine?.invoke(w, h, config) ?: HingeProjection.centeredFold(w, h, config.foldSplitsLong)
                HingeProjection.setUniforms(effect, w, h, tilt, config, pxPerMm, line)
                effect.setInputShader("content", image)
                paint.shader = effect
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    companion object {
        private val discardNativeShader = Shader::class.java
            .getDeclaredMethod("discardNativeInstance")
            .apply { isAccessible = true }

        private fun Shader.releaseNativeInstance() {
            discardNativeShader.invoke(this)
        }
    }
}
