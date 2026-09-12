package com.foldduo.hinge.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import com.foldduo.hinge.AngleRuntime
import com.foldduo.hinge.capture.LiveFrameHub
import com.foldduo.hinge.effect.HingeProjection
import com.foldduo.hinge.effect.HingeTravel
import com.foldduo.hinge.effect.HingeTravelEstimator
import com.foldduo.hinge.effect.EffectPreferences
import com.foldduo.hinge.effect.FrameSmoother
import com.foldduo.hinge.effect.isInnerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** System-wide hinge transition overlay for ZFoldDuo. */
class HingeOverlayService : AccessibilityService() {
    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private data class CoverLayer(
        val displayId: Int,
        val windowManager: WindowManager,
        val view: HingeSceneView,
        val follower: FrameSmoother,
        val acceptsLiveFrames: Boolean,
    )

    private data class OpeningOuterLayer(
        val displayId: Int,
        val windowManager: WindowManager,
        val view: HingeSceneView,
        val follower: FrameSmoother,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var displayManager: DisplayManager
    private var windowManager: WindowManager? = null
    private var windowManagerDisplayId = -1
    private var activeDisplayId = -1
    private var phase = Phase.IDLE
    private var overlay: HingeSceneView? = null
    private var overlayWindowManager: WindowManager? = null
    private var follower: FrameSmoother? = null
    private var innerPanel = false
    private var restArmed = true
    private var waitingForPanel = false
    private val motionTracker = HingeTravelEstimator()
    private var lastAngle = Float.NaN
    private var demoRunning = false
    private var captureGen = 0
    private var dualPreparePending = false
    private var dualDisplayActive = false
    private var openingDualActive = false
    private var openingInnerArmed = false
    private var openingCaptureSourceDisplayId = -1
    private var openingOuterCaptureGen = 0
    private var openingVisualStartAngle = Float.NaN
    private var openingMinimumVisibleUntilMs = 0L
    private var openingReleaseScheduled = false
    private var pendingOpeningOuterFrame: Bitmap? = null
    private var openingOuterLayer: OpeningOuterLayer? = null
    private var closingInnerSnapshot: Bitmap? = null
    private var lastCoverSnapshot: Bitmap? = null
    private var closingHandoffActive = false
    private var closingHandoffGen = 0
    private val heldInnerDisplayIds = mutableSetOf<Int>()
    private var closedCheckInFlight = false
    private var coverLayer: CoverLayer? = null
    private var coverCaptureScheduled = false
    private var coverCaptureInFlight = false
    private var coverCaptureGen = 0
    private var receiverRegistered = false
    private var stationaryAnchorAngle = Float.NaN
    private var stationarySuppressed = false
    private val liveReadyViews = Collections.newSetFromMap(IdentityHashMap<HingeSceneView, Boolean>())
    private val liveFrameDisplays = mutableSetOf<Int>()

    private val stationaryTimeout = Runnable {
        if (
            !stationarySuppressed && lastAngle.isFinite() && stationaryAnchorAngle.isFinite() &&
            abs(lastAngle - stationaryAnchorAngle) <= STATIONARY_ANGLE_TOLERANCE
        ) {
            stationarySuppressed = true
            clearStationaryTransition()
            Log.i(TAG, "cleared all transition state after 1s stationary at angle=$lastAngle")
        }
    }

    private val captureNewPanel = Runnable {
        waitingForPanel = false
        if (
            stationarySuppressed || demoRunning || activeDisplayId < 0 ||
            !lastAngle.isFinite()
        ) return@Runnable
        val tilt = currentTilt()
        if (tilt < HingeProjection.FLAT_EPSILON) {
            restArmed = true
        } else if (phase == Phase.IDLE) {
            restArmed = false
            startCapture(afterSwap = true)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplaysChanged()
        override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged()
        override fun onDisplayChanged(displayId: Int) = onDisplaysChanged()
    }

    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
        receiverRegistered = true
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        LiveFrameHub.setListener(::onLiveFrame)
        // A process can be killed while Samsung's concurrent-display override
        // is active. Always begin from the physical device state we actually see.
        AngleRuntime.releasePreparedCoverDisplay()
        updateActiveDisplay(scheduleCapture = false)
        scope.launch {
            AngleRuntime.sample.collectLatest { sample -> sample?.let { onHinge(it.angle) } }
        }
        Log.i(TAG, "connected; precise embedded ADB; display=$activeDisplayId inner=$innerPanel")
    }

    override fun onDestroy() {
        instance = null
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacks(captureNewPanel)
        handler.removeCallbacks(stationaryTimeout)
        LiveFrameHub.requestDisplays(emptyList())
        LiveFrameHub.setListener(null)
        clearCoverLayer()
        clearOpeningOuterLayer()
        lastCoverSnapshot?.takeUnless(Bitmap::isRecycled)?.recycle()
        lastCoverSnapshot = null
        releaseInnerDisplayWakeLocks()
        releaseOpeningDualDisplay()
        releasePreparedCover()
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun currentTilt() = HingeProjection.tiltFor(lastAngle, EffectPreferences.config.value, innerPanel)

    private fun activeDisplay(): Display? {
        val builtIns = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS)
        val displays = if (builtIns.isNotEmpty()) builtIns else displayManager.displays
        val on = displays.filter { it.state == Display.STATE_ON }
        if (on.isEmpty()) return null
        // During the final close handoff, logical display 0 changes its backing
        // panel from inner to cover. Stay attached to that logical display so a
        // flat cover frame can mask Samsung's display-tree reconfiguration.
        if (closingHandoffActive) {
            return on.firstOrNull { it.displayId == activeDisplayId }
        }
        // On open, concurrent-inner-default gives the inner panel its real
        // full-size task while a separate overlay preserves the outer panel.
        if (openingDualActive) {
            return on.firstOrNull { it.isInnerPanel() } ?: on.first()
        }
        // During a prepared close both panels are ON, but the animation belongs
        // to the inner panel until the hinge is fully closed. Samsung can move
        // that physical panel from logical display 0 to display 1 in concurrent
        // mode, so follow panel identity rather than the old logical ID.
        if (dualDisplayActive && innerPanel) {
            // Samsung briefly reports only the outer display while logical IDs
            // are remapped. Wait for the same physical inner panel on display 1.
            return on.firstOrNull { it.isInnerPanel() }
        }
        if (on.size == 1) return on.first()
        on.firstOrNull {
            it.displayId == activeDisplayId && it.isInnerPanel() == innerPanel
        }?.let { return it }
        return if (lastAngle.isFinite() && lastAngle >= 90f) {
            on.firstOrNull { it.isInnerPanel() } ?: on.first()
        } else {
            on.firstOrNull { !it.isInnerPanel() } ?: on.first()
        }
    }

    private fun onDisplaysChanged() {
        val active = activeDisplay()
        if (active == null) {
            // Concurrent mode remaps the physical inner panel from logical
            // display 0 to 1. There is a short interval where only the cover is
            // reported as ON. Keep the old inner view and its frame alive so it
            // can be reattached as soon as display 1 appears.
            if (dualDisplayActive && innerPanel) {
                scheduleCoverLayer()
                return
            }
            if (phase != Phase.IDLE) removeOverlay()
            if (!dualDisplayActive) clearCoverLayer()
            return
        }
        if (dualDisplayActive && active.isInnerPanel()) {
            holdInnerDisplayAwake(active.displayId)
        }
        // Fold devices have two observable topologies. In some transitions the cover is
        // logical display 1; in others Android keeps logical display 0 and swaps
        // its backing physical panel from 1968x2184 to 1080x2520. Display ID alone
        // therefore cannot identify a panel hand-off.
        if (active.displayId != activeDisplayId || active.isInnerPanel() != innerPanel) {
            updateActiveDisplay(scheduleCapture = true)
        } else if (!waitingForPanel) {
            evaluate()
        }
        coverLayer?.let { layer ->
            val display = displayManager.getDisplay(layer.displayId)
            if (display == null || display.state != Display.STATE_ON || display.isInnerPanel()) {
                clearCoverLayer()
            }
        }
        openingOuterLayer?.let { layer ->
            val display = displayManager.getDisplay(layer.displayId)
            if (display == null || display.state != Display.STATE_ON || display.isInnerPanel()) {
                clearOpeningOuterLayer()
            }
        }
        if (openingDualActive) tryShowOpeningOuterLayer(openingOuterCaptureGen)
        if (dualDisplayActive) scheduleCoverLayer()
    }

    /** Tracks the active physical panel even when Android reuses the logical ID. */
    private fun updateActiveDisplay(scheduleCapture: Boolean): Boolean {
        val display = activeDisplay() ?: return false
        val nextInner = display.isInnerPanel()
        if (display.displayId == activeDisplayId && nextInner == innerPanel) return false
        val oldId = activeDisplayId
        val oldInner = innerPanel
        val preserveClosingHandoff =
            closingHandoffActive && oldId >= 0 && oldId == display.displayId &&
                oldInner && !nextInner && overlay != null
        val preparedCover = if (!nextInner) {
            coverLayer?.takeIf { it.displayId == display.displayId }
        } else {
            null
        }
        val samePhysicalPanel = preparedCover == null && oldId >= 0 && oldInner == nextInner
        val carrySource = if (samePhysicalPanel) {
            overlay?.snapshot ?: closingInnerSnapshot
        } else {
            null
        }
        // A view and the live-frame retire queue own their bitmap. Give the next
        // logical display an independent copy so a delayed release cannot invalidate it.
        val carriedBitmap = carrySource
            ?.takeUnless(Bitmap::isRecycled)
            ?.let(::duplicateFrame)
        val carriedTilt = if (carriedBitmap != null) {
            overlay?.tilt ?: currentTilt()
        } else {
            null
        }
        if (oldId >= 0 && !oldInner && nextInner) {
            overlay?.snapshot
                ?.takeUnless(Bitmap::isRecycled)
                ?.let(::rememberCoverFrame)
        }
        activeDisplayId = display.displayId
        innerPanel = nextInner
        if (dualDisplayActive && innerPanel) holdInnerDisplayAwake(activeDisplayId)
        // Only cover -> inner teaches the cover phase's real switch angle.
        // The reverse transition on Samsung has much stronger hysteresis and
        // often reaches the cover at only 4-7°, so it must not overwrite it.
        if (
            oldId >= 0 && !oldInner && innerPanel && lastAngle.isFinite() &&
            !openingDualActive
        ) {
            EffectPreferences.learnPanelSwitch(lastAngle)
        }
        if (closingInnerSnapshot === carrySource) closingInnerSnapshot = null
        if (phase != Phase.IDLE && !preserveClosingHandoff) removeOverlay()
        // carrySource may have already been submitted to RenderThread by a
        // detached view. Recycling it here can invalidate that queued draw.
        windowManager = null
        windowManagerDisplayId = -1
        handler.removeCallbacks(captureNewPanel)
        if (preserveClosingHandoff) {
            waitingForPanel = false
            follower?.cancel()
            follower = null
            phase = Phase.SHOWING
            syncLiveCaptureRequests()
            Log.i(TAG, "cover handoff preserved on logical display=${display.displayId}")
        } else if (preparedCover != null) {
            coverLayer = null
            coverCaptureGen++
            coverCaptureScheduled = false
            coverCaptureInFlight = false
            waitingForPanel = false
            windowManager = preparedCover.windowManager
            windowManagerDisplayId = preparedCover.displayId
            overlay = preparedCover.view
            overlayWindowManager = preparedCover.windowManager
            follower = preparedCover.follower
            phase = Phase.SHOWING
            restArmed = false
            follower?.setTarget(currentTilt())
            Log.i(TAG, "promoted cover layer on display=${display.displayId}")
        } else if (carriedBitmap != null && carriedTilt != null) {
            waitingForPanel = false
            handler.postDelayed({
                if (activeDisplayId == display.displayId && innerPanel == nextInner && phase == Phase.IDLE) {
                    Log.i(TAG, "reattaching physical panel on display=${display.displayId} tilt=$carriedTilt")
                    show(carriedBitmap, carriedTilt, display.displayId)
                    closingInnerSnapshot = null
                    follower?.setTarget(currentTilt())
                } else {
                    if (dualDisplayActive && nextInner) closingInnerSnapshot = carriedBitmap
                    else carriedBitmap.recycle()
                }
            }, PANEL_REATTACH_MS)
        } else {
            waitingForPanel = scheduleCapture && oldId >= 0
            if (waitingForPanel) handler.postDelayed(captureNewPanel, PANEL_STABLE_MS)
        }
        Log.i(TAG, "panel $oldId/$oldInner -> $activeDisplayId/$innerPanel angle=$lastAngle")
        return true
    }

    /** GPU copy: shell capture buffers intentionally do not permit CPU readback. */
    private fun duplicateFrame(source: Bitmap): Bitmap? {
        val node = RenderNode("ZFoldDuoFrameCopy")
        return runCatching {
            node.setPosition(0, 0, source.width, source.height)
            val canvas = node.beginRecording()
            try {
                canvas.drawBitmap(source, 0f, 0f, null)
            } finally {
                node.endRecording()
            }
            (createHardwareBitmap.value.invoke(null, node, source.width, source.height) as Bitmap).apply {
                density = source.density
            }
        }.onFailure { error ->
            Log.e(TAG, "could not carry inner frame", error)
        }.getOrNull().also {
            node.discardDisplayList()
        }
    }

    private fun onHinge(angle: Float) {
        val previousAngle = lastAngle
        lastAngle = angle
        if (trackStationary(angle)) return
        val previousMotion = motionTracker.motion
        val motion = motionTracker.update(angle, SystemClock.uptimeMillis())
        if (motion != previousMotion) {
            Log.i(TAG, "motion $previousMotion -> $motion angle=$angle")
        }

        // The stable motion tracker intentionally waits for a sizeable reversal.
        // For display power that is too late: arm only at the physical closed end,
        // then turn the inner panel on as soon as the private hinge angle rises.
        if (angle <= OPENING_INNER_ARM_HINGE) openingInnerArmed = true
        if (
            openingInnerArmed && !openingDualActive && !dualDisplayActive &&
            !closingHandoffActive && !innerPanel &&
            previousAngle.isFinite() && angle >= OPENING_INNER_WAKE_HINGE && angle > previousAngle
        ) {
            openingInnerArmed = false
            openingDualActive = true
            prepareOpeningDisplays(previousAngle)
            Log.i(TAG, "inner display enabled at opening angle=$angle")
        }
        if (openingDualActive) {
            if (angle >= OPENING_INNER_RELEASE_HINGE) {
                requestOpeningRelease()
            } else if (
                angle <= OPENING_INNER_ARM_HINGE &&
                previousAngle.isFinite() && angle < previousAngle
            ) {
                releaseOpeningDualDisplay()
            }
        }
        if (motion == HingeTravel.OPENING) {
            dualPreparePending = false
            // Near closed, hinge quantization can briefly report OPENING
            // during an uninterrupted close. Keep both panels alive until either
            // the device is physically CLOSED or reopening is unmistakable.
            if (dualDisplayActive && angle >= DUAL_RELEASE_OPEN_HINGE) {
                releasePreparedCover()
            } else if (
                !dualDisplayActive && !openingDualActive && !closingHandoffActive &&
                previousMotion != HingeTravel.OPENING
            ) {
                AngleRuntime.releasePreparedCoverDisplay()
            }
        }
        if (
            innerPanel && !openingDualActive && !dualDisplayActive && !closingHandoffActive &&
            !dualPreparePending &&
            motion == HingeTravel.CLOSING &&
            angle in COVER_PREPARE_DONE_HINGE..COVER_PREPARE_HINGE &&
            displayManager.getDisplay(activeDisplayId)?.state == Display.STATE_ON
        ) {
            prepareDualDisplayAfterInnerSnapshot()
        }
        if (dualDisplayActive) {
            scheduleCoverLayer()
        }
        coverLayer?.follower?.setTarget(
            HingeProjection.coverTiltForHinge(angle, EffectPreferences.config.value),
        )
        openingOuterLayer?.follower?.setTarget(
            HingeProjection.coverTiltForHinge(angle, EffectPreferences.config.value),
        )
        if (updateActiveDisplay(scheduleCapture = true)) return
        evaluate()
        val tilt = HingeProjection.tiltFor(angle, EffectPreferences.config.value, innerPanel)
        if (
            tilt < HingeProjection.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning &&
            !(dualDisplayActive && innerPanel)
        ) {
            dismiss(FADE_OUT_FLAT_MS)
        } else {
            follower?.setTarget(tilt)
        }
    }

    private fun evaluate() {
        if (stationarySuppressed || demoRunning || waitingForPanel || activeDisplayId < 0) return
        if (!lastAngle.isFinite()) return
        val tilt = currentTilt()
        if (tilt < HingeProjection.FLAT_EPSILON) {
            restArmed = true
            return
        }
        if (phase != Phase.IDLE) return
        if (restArmed && tilt >= REST_LEAVE_TILT) {
            restArmed = false
            startCapture(afterSwap = false)
        }
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        if (stationarySuppressed) return
        val displayId = activeDisplayId
        if (displayId < 0) return
        phase = Phase.CAPTURING
        capture(++captureGen, 1, afterSwap, startTilt, displayId)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?, displayId: Int) {
        val started = SystemClock.uptimeMillis()
        fun stale() = gen != captureGen || phase != Phase.CAPTURING || displayId != activeDisplayId
        fun retry() {
            val wait = (started + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({
                if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt, displayId)
            }, wait)
        }
        handler.postDelayed({
            if (!stale()) {
                Log.w(TAG, "capture timed out")
                phase = Phase.IDLE
                demoRunning = false
            }
        }, CAPTURE_TIMEOUT_MS)

        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCaptured(bitmap, afterSwap, startTilt, started, displayId)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                    } else if (black && !demoRunning) {
                        bitmap.recycle()
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, started, displayId)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_CAPTURE_ATTEMPTS) {
                    retry()
                } else {
                    Log.w(TAG, "screenshot failed: $errorCode")
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(
        bitmap: Bitmap,
        afterSwap: Boolean,
        startTilt: Float?,
        started: Long,
        displayId: Int,
    ) {
        if (phase != Phase.CAPTURING || displayId != activeDisplayId) {
            bitmap.recycle()
            return
        }
        val naturalTilt = startTilt ?: currentTilt()
        if (naturalTilt < HingeProjection.FLAT_EPSILON) {
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        // Both physical panels meet at the same fully-frosted pose. Samsung's
        // cover can appear at only ~5° on close, long after the inner panel
        // disappeared, so deriving this start pose from the late angle causes
        // the visible discontinuity. Start the new panel at the shared peak,
        // then resolve toward its live angle.
        val peak = HingeProjection.MAX_TILT * EffectPreferences.config.value.intensity.coerceAtMost(1f)
        val tilt = startTilt ?: if (afterSwap) peak else naturalTilt
        Log.i(TAG, "showing display=$displayId ${bitmap.width}x${bitmap.height} angle=$lastAngle tilt=$tilt capture=${SystemClock.uptimeMillis() - started}ms")
        show(bitmap, tilt, displayId)
    }

    private fun isMostlyBlack(hw: Bitmap): Boolean {
        val bitmap = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
        try {
            val samples = 24
            var brightest = 0
            for (iy in 0 until samples) {
                val y = ((iy + 0.5f) * bitmap.height / samples).toInt()
                for (ix in 0 until samples) {
                    val x = ((ix + 0.5f) * bitmap.width / samples).toInt()
                    val color = bitmap.getPixel(x, y)
                    val sum = ((color shr 16) and 255) + ((color shr 8) and 255) + (color and 255)
                    if (sum > brightest) brightest = sum
                }
            }
            return brightest < BLACK_THRESHOLD
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(bitmap: Bitmap, startTilt: Float, displayId: Int) {
        val display = displayManager.getDisplay(displayId)
            ?: run { bitmap.recycle(); phase = Phase.IDLE; return }
        if (displayId != activeDisplayId || display.state != Display.STATE_ON) {
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        val displayContext = createDisplayContext(display)
        val wm = if (windowManager != null && windowManagerDisplayId == displayId) {
            windowManager!!
        } else displayContext
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            .also {
                windowManager = it
                windowManagerDisplayId = displayId
            }
        val inner = innerPanel
        val view = HingeSceneView(displayContext, bitmap, { w, h, config -> HingeProjection.foldFor(inner, w, h, config) }).apply {
            config = EffectPreferences.config.value
            tilt = startTilt
        }
        try {
            wm.addView(view, overlayParams("ZFoldDuoInnerProjection"))
            excludeFromScreenCapture(view)
        } catch (error: Exception) {
            Log.e(TAG, "addView failed", error)
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        overlay = view
        overlayWindowManager = wm
        phase = Phase.SHOWING
        if (!inner) rememberCoverFrame(bitmap)
        follower = FrameSmoother { tilt ->
            view.tilt = tilt
            if (
                tilt < HingeProjection.FLAT_EPSILON && !demoRunning &&
                !(dualDisplayActive && innerPanel)
            ) dismiss(FADE_OUT_FLAT_MS)
        }.also { it.snap(startTilt) }
        if (
            dualPreparePending && !openingDualActive &&
            motionTracker.motion == HingeTravel.CLOSING &&
            innerPanel
        ) {
            activateDualDisplay()
        }
    }

    private fun dismiss(fadeMs: Long) {
        val view = overlay ?: return
        // The logical display can be remapped while this fade is running.
        // Capture the WindowManager that actually owns this view; the service
        // field may already point at the next panel by the end callback.
        val owner = overlayWindowManager ?: windowManager
        follower?.cancel()
        follower = null
        overlay = null
        overlayWindowManager = null
        phase = Phase.IDLE
        view.animate().alpha(0f).setDuration(fadeMs).setInterpolator(DecelerateInterpolator())
            .withEndAction { detach(view, owner) }.start()
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        val view = overlay ?: return
        follower?.cancel()
        follower = null
        overlay = null
        val owner = overlayWindowManager ?: windowManager
        overlayWindowManager = null
        detach(view, owner)
    }

    private fun detach(view: HingeSceneView, owner: WindowManager?, attempt: Int = 0) {
        liveReadyViews.remove(view)
        if (view.isAttachedToWindow) {
            runCatching {
                checkNotNull(owner) { "overlay owner is unavailable" }
                owner.removeViewImmediate(view)
            }.onFailure { error ->
                Log.e(TAG, "overlay detach failed (attempt=$attempt)", error)
            }
        }
        if (view.isAttachedToWindow) {
            // Never clear a renderer that is still attached: this is an opaque
            // full-screen view, so doing so would leave the cover solid black.
            if (attempt < MAX_DETACH_ATTEMPTS) {
                handler.postDelayed(
                    { detach(view, owner, attempt + 1) },
                    DETACH_RETRY_MS,
                )
            } else {
                Log.e(TAG, "overlay remained attached; renderer kept alive")
            }
            syncLiveCaptureRequests()
            return
        }
        view.release()
        syncLiveCaptureRequests()
    }

    private fun coverDisplay(): Display? {
        val builtIns = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS)
        val displays = if (builtIns.isNotEmpty()) builtIns else displayManager.displays
        return displays.firstOrNull { it.state == Display.STATE_ON && !it.isInnerPanel() }
    }

    private fun scheduleCoverLayer() {
        if (!dualDisplayActive || coverLayer != null || coverCaptureScheduled || coverCaptureInFlight) return
        val display = coverDisplay() ?: return
        val gen = ++coverCaptureGen
        val displayId = display.displayId
        coverCaptureScheduled = true
        handler.postDelayed({
            coverCaptureScheduled = false
            val current = displayManager.getDisplay(displayId)
            if (
                gen != coverCaptureGen || !dualDisplayActive || coverLayer != null ||
                current == null || current.state != Display.STATE_ON || current.isInnerPanel()
            ) return@postDelayed
            val cached = lastCoverSnapshot
                ?.takeUnless(Bitmap::isRecycled)
                ?.let(::duplicateFrame)
            if (cached != null) {
                showCover(cached, displayId, SystemClock.uptimeMillis(), acceptsLiveFrames = false)
                return@postDelayed
            }
            coverCaptureInFlight = true
            captureCover(gen, attempt = 1, displayId, needsSettledFrame = true)
        }, COVER_CAPTURE_DELAY_MS)
    }

    private fun captureCover(
        gen: Int,
        attempt: Int,
        displayId: Int,
        needsSettledFrame: Boolean,
    ) {
        val started = SystemClock.uptimeMillis()
        fun stale(): Boolean {
            val display = displayManager.getDisplay(displayId)
            return gen != coverCaptureGen || !dualDisplayActive || coverLayer != null ||
                display == null || display.state != Display.STATE_ON || display.isInnerPanel()
        }
        fun finish() {
            if (gen == coverCaptureGen) coverCaptureInFlight = false
        }
        fun retry(stillNeedsSettledFrame: Boolean) {
            val wait = (started + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({
                if (stale()) finish()
                else captureCover(gen, attempt + 1, displayId, stillNeedsSettledFrame)
            }, wait)
        }

        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    bitmap?.recycle()
                    finish()
                    return
                }
                if (bitmap == null) {
                    finish()
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        finish()
                    } else if (black) {
                        bitmap.recycle()
                        if (attempt < MAX_COVER_CAPTURE_ATTEMPTS) retry(needsSettledFrame) else {
                            Log.w(TAG, "cover screenshot stayed black")
                            finish()
                        }
                    } else if (needsSettledFrame) {
                        // Samsung initially draws a transition snapshot from the
                        // inner display onto the newly powered cover. Never use
                        // that first frame as the frozen cover animation source.
                        bitmap.recycle()
                        if (attempt < MAX_COVER_CAPTURE_ATTEMPTS) retry(false) else {
                            Log.w(TAG, "no settled cover screenshot available")
                            finish()
                        }
                    } else {
                        showCover(bitmap, displayId, started, acceptsLiveFrames = true)
                        finish()
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) {
                    finish()
                } else if (
                    errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                    attempt < MAX_COVER_CAPTURE_ATTEMPTS
                ) {
                    retry(needsSettledFrame)
                } else {
                    Log.w(TAG, "cover screenshot failed: $errorCode")
                    finish()
                }
            }
        })
    }

    private fun showCover(
        bitmap: Bitmap,
        displayId: Int,
        started: Long,
        acceptsLiveFrames: Boolean,
    ) {
        val display = displayManager.getDisplay(displayId)
        if (
            !dualDisplayActive || coverLayer != null || display == null ||
            display.state != Display.STATE_ON || display.isInnerPanel()
        ) {
            bitmap.recycle()
            return
        }
        val displayContext = createDisplayContext(display)
        val wm = displayContext
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val startTilt = HingeProjection.coverTiltForHinge(lastAngle, EffectPreferences.config.value)
        if (startTilt < HingeProjection.FLAT_EPSILON) {
            bitmap.recycle()
            return
        }
        val view = HingeSceneView(
            displayContext,
            bitmap,
            { w, h, config -> HingeProjection.foldFor(false, w, h, config) },
        ).apply {
            config = EffectPreferences.config.value
            tilt = startTilt
        }
        try {
            wm.addView(view, overlayParams("ZFoldDuoCoverProjection"))
            excludeFromScreenCapture(view)
        } catch (error: Exception) {
            Log.e(TAG, "cover addView failed", error)
            bitmap.recycle()
            return
        }
        val coverFollower = FrameSmoother { tilt ->
            view.tilt = tilt
            if (tilt < HingeProjection.FLAT_EPSILON) {
                when {
                    coverLayer?.view === view -> dismissCoverLayer(FADE_OUT_FLAT_MS)
                    overlay === view && !demoRunning -> dismiss(FADE_OUT_FLAT_MS)
                }
            }
        }.also { it.snap(startTilt) }
        coverLayer = CoverLayer(
            displayId,
            wm,
            view,
            coverFollower,
            acceptsLiveFrames,
        )
        coverFollower.setTarget(HingeProjection.coverTiltForHinge(lastAngle, EffectPreferences.config.value))
        Log.i(
            TAG,
            "showing cover display=$displayId ${bitmap.width}x${bitmap.height} " +
                "angle=$lastAngle tilt=$startTilt capture=${SystemClock.uptimeMillis() - started}ms",
        )
    }

    private fun dismissCoverLayer(fadeMs: Long) {
        val layer = coverLayer ?: return
        coverLayer = null
        layer.follower.cancel()
        layer.view.animate().alpha(0f).setDuration(fadeMs).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                liveReadyViews.remove(layer.view)
                runCatching { layer.windowManager.removeViewImmediate(layer.view) }
                layer.view.release()
                syncLiveCaptureRequests()
            }.start()
    }

    private fun clearCoverLayer() {
        val layer = coverLayer ?: return
        coverLayer = null
        layer.follower.cancel()
        liveReadyViews.remove(layer.view)
        runCatching { layer.windowManager.removeViewImmediate(layer.view) }
        layer.view.release()
        syncLiveCaptureRequests()
    }

    private fun prepareOpeningDisplays(startAngle: Float) {
        openingCaptureSourceDisplayId = activeDisplayId
        openingVisualStartAngle = startAngle
        openingMinimumVisibleUntilMs = 0L
        openingReleaseScheduled = false
        val gen = ++openingOuterCaptureGen
        // Capture the real cover before Samsung remaps logical display 0 to the
        // inner panel. Only after that frame is safe do we enable both panels.
        captureOpeningOuterFrame(gen, attempt = 1)
    }

    private fun captureOpeningOuterFrame(gen: Int, attempt: Int) {
        val sourceDisplayId = openingCaptureSourceDisplayId
        if (!openingDualActive || gen != openingOuterCaptureGen || sourceDisplayId < 0) return
        takeScreenshot(sourceDisplayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (!openingDualActive || gen != openingOuterCaptureGen) {
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) return
                finishOpeningDisplayPreparation(gen, bitmap)
            }

            override fun onFailure(errorCode: Int) {
                if (!openingDualActive || gen != openingOuterCaptureGen) return
                val currentOuter = overlay?.snapshot
                    ?.takeUnless(Bitmap::isRecycled)
                    ?.let(::duplicateFrame)
                if (currentOuter != null) {
                    finishOpeningDisplayPreparation(gen, currentOuter)
                    return
                }
                if (
                    errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                    attempt < MAX_CAPTURE_ATTEMPTS
                ) {
                    handler.postDelayed(
                        { captureOpeningOuterFrame(gen, attempt + 1) },
                        SCREENSHOT_MIN_INTERVAL_MS,
                    )
                } else {
                    openingDualActive = false
                    Log.w(TAG, "opening cover screenshot failed: $errorCode")
                }
            }
        })
    }

    private fun finishOpeningDisplayPreparation(gen: Int, bitmap: Bitmap) {
        if (!openingDualActive || gen != openingOuterCaptureGen) {
            bitmap.recycle()
            return
        }
        // Opening consumes its own cover frame at 90 degrees. Keep an
        // independent copy for the next close, where Samsung's concurrent
        // cover display has no drawable content of its own.
        rememberCoverFrame(bitmap)
        pendingOpeningOuterFrame?.takeUnless(Bitmap::isRecycled)?.recycle()
        pendingOpeningOuterFrame = bitmap
        AngleRuntime.prepareCoverDisplay()
        tryShowOpeningOuterLayer(gen)
    }

    private fun tryShowOpeningOuterLayer(gen: Int) {
        if (!openingDualActive || gen != openingOuterCaptureGen || openingOuterLayer != null) return
        val bitmap = pendingOpeningOuterFrame ?: return
        val builtIns = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS)
        val displays = if (builtIns.isNotEmpty()) builtIns else displayManager.displays
        val display = displays.firstOrNull {
            it.displayId != openingCaptureSourceDisplayId &&
                it.state == Display.STATE_ON && !it.isInnerPanel()
        }
        if (display == null) {
            handler.postDelayed(
                { tryShowOpeningOuterLayer(gen) },
                OPENING_INNER_DISPLAY_RETRY_MS,
            )
            return
        }
        pendingOpeningOuterFrame = null
        showOpeningOuterLayer(bitmap, display)
    }

    private fun showOpeningOuterLayer(bitmap: Bitmap, display: Display) {
        if (!openingDualActive || openingOuterLayer != null) {
            bitmap.recycle()
            return
        }
        val displayContext = createDisplayContext(display)
        val wm = displayContext
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val config = EffectPreferences.config.value
        val targetTilt = HingeProjection.coverTiltForHinge(lastAngle, config)
        val visualStartAngle = openingVisualStartAngle.takeIf(Float::isFinite) ?: lastAngle
        val startTilt = HingeProjection.coverTiltForHinge(visualStartAngle, config)
        val view = HingeSceneView(
            displayContext,
            bitmap,
            { w, h, config -> HingeProjection.foldFor(false, w, h, config) },
        ).apply {
            this.config = config
            tilt = startTilt
        }
        try {
            wm.addView(view, overlayParams("ZFoldDuoCoverProjectionTransition"))
            excludeFromScreenCapture(view)
        } catch (error: Exception) {
            Log.e(TAG, "opening outer addView failed", error)
            bitmap.recycle()
            return
        }
        val outerFollower = FrameSmoother { tilt -> view.tilt = tilt }.also { it.snap(startTilt) }
        openingOuterLayer = OpeningOuterLayer(
            display.displayId,
            wm,
            view,
            outerFollower,
        )
        if (abs(targetTilt - startTilt) >= OPENING_CATCH_UP_MIN_DEGREES) {
            openingMinimumVisibleUntilMs =
                SystemClock.uptimeMillis() + OPENING_CATCH_UP_VISIBLE_MS
        }
        outerFollower.setTarget(targetTilt)
        Log.i(TAG, "showing preserved opening cover on display=${display.displayId}")
        if (lastAngle >= OPENING_INNER_RELEASE_HINGE) requestOpeningRelease()
    }

    /** Wait until the asynchronously-created cover layer has rendered its catch-up motion. */
    private fun requestOpeningRelease() {
        if (!openingDualActive || openingOuterLayer == null) return
        val delayMs =
            (openingMinimumVisibleUntilMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (delayMs == 0L) {
            releaseOpeningDualDisplay()
            return
        }
        if (openingReleaseScheduled) return
        openingReleaseScheduled = true
        val gen = openingOuterCaptureGen
        handler.postDelayed({
            openingReleaseScheduled = false
            if (
                openingDualActive && gen == openingOuterCaptureGen &&
                lastAngle >= OPENING_INNER_RELEASE_HINGE
            ) {
                releaseOpeningDualDisplay()
            }
        }, delayMs)
    }

    private fun clearOpeningOuterLayer() {
        openingOuterCaptureGen++
        openingCaptureSourceDisplayId = -1
        openingVisualStartAngle = Float.NaN
        openingMinimumVisibleUntilMs = 0L
        openingReleaseScheduled = false
        pendingOpeningOuterFrame?.takeUnless(Bitmap::isRecycled)?.recycle()
        pendingOpeningOuterFrame = null
        val layer = openingOuterLayer ?: return
        openingOuterLayer = null
        layer.follower.cancel()
        liveReadyViews.remove(layer.view)
        runCatching { layer.windowManager.removeViewImmediate(layer.view) }
        layer.view.release()
        syncLiveCaptureRequests()
    }

    private fun overlayParams(windowTitle: String) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = windowTitle
    }

    /** Keep the physical overlay visible while omitting it from mirrors and recordings. */
    private fun excludeFromScreenCapture(view: HingeSceneView) {
        view.post {
            runCatching {
                val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view)
                    ?: error("ViewRootImpl unavailable")
                val surface = root.javaClass.getDeclaredMethod("getSurfaceControl").invoke(root)
                    as SurfaceControl
                SurfaceControl.Transaction().use { transaction ->
                    SurfaceControl.Transaction::class.java.getDeclaredMethod(
                        "setSkipScreenshot",
                        SurfaceControl::class.java,
                        Boolean::class.javaPrimitiveType,
                    ).invoke(transaction, surface, true)
                    transaction.addTransactionCommittedListener(mainExecutor) {
                        if (!view.isAttachedToWindow) return@addTransactionCommittedListener
                        liveReadyViews.add(view)
                        syncLiveCaptureRequests()
                        Log.i(TAG, "overlay excluded from screen capture")
                    }
                    transaction.apply()
                }
            }.onFailure { error ->
                Log.e(TAG, "could not exclude overlay from screen capture", error)
            }
        }
    }

    private fun syncLiveCaptureRequests() {
        val displays = buildSet {
            overlay
                ?.takeIf { liveReadyViews.contains(it) && !(closingHandoffActive && innerPanel) }
                ?.let { add(activeDisplayId) }
            coverLayer
                ?.takeIf { it.acceptsLiveFrames && liveReadyViews.contains(it.view) }
                ?.let { add(it.displayId) }
            openingOuterLayer
                ?.takeIf { liveReadyViews.contains(it.view) }
                ?.takeIf { openingCaptureSourceDisplayId >= 0 }
                ?.let { add(openingCaptureSourceDisplayId) }
        }
        LiveFrameHub.requestDisplays(displays)
    }

    /** Preserve the real cover frame seen before Android switches to the inner panel. */
    private fun rememberCoverFrame(source: Bitmap) {
        val copy = source.takeUnless(Bitmap::isRecycled)?.let(::duplicateFrame) ?: return
        val previous = lastCoverSnapshot
        lastCoverSnapshot = copy
        if (previous != null && !previous.isRecycled) previous.recycle()
    }

    private fun onLiveFrame(displayId: Int, bitmap: Bitmap, timestampNanos: Long) {
        val views = buildList {
            overlay?.takeIf { displayId == activeDisplayId && liveReadyViews.contains(it) }?.let(::add)
            coverLayer
                ?.takeIf { it.displayId == displayId && liveReadyViews.contains(it.view) }
                ?.view
                ?.let(::add)
            openingOuterLayer
                ?.takeIf {
                    displayId == openingCaptureSourceDisplayId && liveReadyViews.contains(it.view)
                }
                ?.view
                ?.let(::add)
        }.distinct()
        if (views.isEmpty()) {
            bitmap.recycle()
            return
        }
        views.forEachIndexed { index, view ->
            val frame = if (index == views.lastIndex) bitmap else duplicateFrame(bitmap) ?: return@forEachIndexed
            val previous = view.updateSnapshot(frame)
            if (closingInnerSnapshot === previous) closingInnerSnapshot = frame
            // Do not recycle previous. A frame swap updates the Java-side
            // shader immediately, but RenderThread may still own the previous
            // display list. GC/native reference tracking retires it safely.
        }
        if (liveFrameDisplays.add(displayId)) {
            Log.i(
                TAG,
                "live frames display=$displayId ${bitmap.width}x${bitmap.height} ts=$timestampNanos",
            )
        }
    }

    private fun releasePreparedCover() {
        dualPreparePending = false
        closingInnerSnapshot = null
        if (!dualDisplayActive) return
        dualDisplayActive = false
        closedCheckInFlight = false
        releaseInnerDisplayWakeLocks()
        coverCaptureGen++
        coverCaptureScheduled = false
        coverCaptureInFlight = false
        clearCoverLayer()
        AngleRuntime.releasePreparedCoverDisplay()
        Log.i(TAG, "prepared cover display released")
    }

    private fun releaseOpeningDualDisplay() {
        val wasActive = openingDualActive
        openingDualActive = false
        clearOpeningOuterLayer()
        if (!wasActive) return
        AngleRuntime.releasePreparedCoverDisplay()
        Log.i(TAG, "opening inner-display preparation released at angle=$lastAngle")
    }

    private fun prepareDualDisplayAfterInnerSnapshot() {
        dualPreparePending = true
        if (phase == Phase.SHOWING && overlay != null) {
            activateDualDisplay()
        } else if (phase == Phase.IDLE) {
            restArmed = false
            startCapture(afterSwap = false)
        }
    }

    private fun activateDualDisplay() {
        if (
            !dualPreparePending || dualDisplayActive || innerPanel.not() ||
            motionTracker.motion != HingeTravel.CLOSING || overlay == null
        ) return
        dualPreparePending = false
        dualDisplayActive = true
        closingInnerSnapshot = overlay?.snapshot
        // Keep both logical IDs awake across Samsung's 0 -> 1 remap of the same
        // physical inner panel. Releasing display 0 before display 1 is ready
        // creates the visible black gap reported during closing.
        holdInnerDisplayAwake(activeDisplayId)
        holdInnerDisplayAwake(CONCURRENT_COVER_DISPLAY_ID)
        AngleRuntime.prepareCoverDisplay()
        handler.postDelayed({ checkPhysicalClosed() }, CLOSED_CHECK_INTERVAL_MS)
        Log.i(
            TAG,
            "cover display prepared at angle=$lastAngle after inner snapshot; " +
                "holding inner displays=$heldInnerDisplayIds",
        )
    }

    /**
     * Put the already-rendered cover frame on logical display 0 while that ID
     * still drives the now-hidden inner panel. State 5 then moves display 0 to
     * the physical cover without ever exposing an uncomposed frame there.
     */
    private fun armClosedDisplayHandoff(): Boolean {
        val view = overlay ?: return false
        val source = coverLayer?.view?.snapshot
            ?.takeUnless(Bitmap::isRecycled)
            ?: lastCoverSnapshot?.takeUnless(Bitmap::isRecycled)
            ?: return false
        val frame = duplicateFrame(source) ?: return false
        val previous = view.updateSnapshot(frame)
        if (closingInnerSnapshot === previous) closingInnerSnapshot = null
        follower?.cancel()
        follower = null
        view.animate().cancel()
        view.alpha = 1f
        view.tilt = 0f
        closingHandoffActive = true
        closingHandoffGen++
        syncLiveCaptureRequests()
        Log.i(TAG, "armed flat cover frame on logical display=$activeDisplayId")
        return true
    }

    private fun finishClosedDisplayHandoff(gen: Int, attempt: Int = 0) {
        if (!closingHandoffActive || gen != closingHandoffGen) return
        updateActiveDisplay(scheduleCapture = false)
        if (innerPanel) {
            handler.postDelayed(
                { finishClosedDisplayHandoff(gen, attempt + 1) },
                CLOSED_HANDOFF_RETRY_MS,
            )
            return
        }
        closingHandoffActive = false
        clearCoverLayer()
        syncLiveCaptureRequests()
        // A property animation can be suspended as Samsung puts the device to
        // sleep at the CLOSED posture. Remove synchronously while the display
        // wake locks are still held, otherwise an opaque ZFoldDuoInnerProjection window can
        // survive the sleep transition and make the cover look powered off.
        removeOverlay()
        releaseInnerDisplayWakeLocks()
        Log.i(TAG, "closed cover handoff finished on display=$activeDisplayId")
    }

    private fun checkPhysicalClosed() {
        if (!dualDisplayActive || closedCheckInFlight) return
        closedCheckInFlight = true
        AngleRuntime.isPreparedCoverDisplayPhysicallyClosed { closed ->
            closedCheckInFlight = false
            if (!dualDisplayActive) return@isPreparedCoverDisplayPhysicallyClosed
            if (closed) {
                val masked = armClosedDisplayHandoff()
                val handoffGen = closingHandoffGen
                AngleRuntime.finishClosedDisplayHandoff {
                    if (!dualDisplayActive) return@finishClosedDisplayHandoff
                    dualDisplayActive = false
                    closingInnerSnapshot = null
                    motionTracker.force(HingeTravel.CLOSING, lastAngle)
                    updateActiveDisplay(scheduleCapture = !masked)
                    if (masked) {
                        handler.postDelayed(
                            { finishClosedDisplayHandoff(handoffGen) },
                            CLOSED_HANDOFF_HOLD_MS,
                        )
                    } else {
                        releaseInnerDisplayWakeLocks()
                    }
                    Log.i(TAG, "physical close confirmed; cover promoted before reset")
                }
            } else {
                handler.postDelayed({ checkPhysicalClosed() }, CLOSED_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun holdInnerDisplayAwake(displayId: Int) {
        if (displayId < 0 || !heldInnerDisplayIds.add(displayId)) return
        AngleRuntime.setDisplayWakeLock(displayId, held = true)
        Log.i(TAG, "holding inner display awake: $displayId")
    }

    private fun releaseInnerDisplayWakeLocks() {
        val displayIds = heldInnerDisplayIds.toList()
        heldInnerDisplayIds.clear()
        displayIds.forEach { displayId ->
            AngleRuntime.setDisplayWakeLock(displayId, held = false)
            Log.i(TAG, "released inner display wake lock: $displayId")
        }
    }

    /**
     * A stopped fold is no longer a transition. Suppress the current angle
     * until real movement resumes, so cleanup cannot immediately recreate the
     * same overlay on the next sensor sample.
     */
    private fun trackStationary(angle: Float): Boolean {
        if (!stationaryAnchorAngle.isFinite()) {
            stationaryAnchorAngle = angle
            handler.postDelayed(stationaryTimeout, STATIONARY_TIMEOUT_MS)
            return false
        }
        if (abs(angle - stationaryAnchorAngle) > STATIONARY_ANGLE_TOLERANCE) {
            stationaryAnchorAngle = angle
            handler.removeCallbacks(stationaryTimeout)
            handler.postDelayed(stationaryTimeout, STATIONARY_TIMEOUT_MS)
            if (stationarySuppressed) {
                stationarySuppressed = false
                restArmed = true
                Log.i(TAG, "transition rearmed by hinge movement at angle=$angle")
            }
            return false
        }
        return stationarySuppressed
    }

    private fun clearStationaryTransition() {
        handler.removeCallbacks(captureNewPanel)
        waitingForPanel = false
        captureGen++
        demoRunning = false
        dualPreparePending = false
        dualDisplayActive = false
        openingDualActive = false
        openingOuterCaptureGen++
        closingHandoffActive = false
        closingHandoffGen++
        closingInnerSnapshot = null
        closedCheckInFlight = false
        coverCaptureGen++
        coverCaptureScheduled = false
        coverCaptureInFlight = false
        clearCoverLayer()
        clearOpeningOuterLayer()
        removeOverlay()
        follower?.cancel()
        follower = null
        restArmed = false
        releaseInnerDisplayWakeLocks()
        AngleRuntime.releasePreparedCoverDisplay()
        LiveFrameHub.requestDisplays(emptyList())
    }

    fun playDemo(durationMs: Long = 1_400) {
        if (phase != Phase.IDLE || demoRunning) return
        demoRunning = true
        val inner = innerPanel
        val peak = HingeProjection.MAX_TILT * EffectPreferences.config.value.intensity.coerceAtMost(1f)
        startCapture(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val active = follower ?: run { demoRunning = false; return@postDelayed }
            active.tauS = durationMs / 4_000f
            if (inner) {
                active.setTarget(0f)
                handler.postDelayed({ demoRunning = false; dismiss(FADE_OUT_FLAT_MS) }, durationMs)
            } else {
                active.setTarget(peak)
                handler.postDelayed({ active.setTarget(0f) }, durationMs)
                handler.postDelayed({ demoRunning = false; dismiss(FADE_OUT_FLAT_MS) }, durationMs * 2)
            }
        }, 450)
    }

    companion object {
        private const val TAG = "ZFoldDuoEngine"
        const val ACTION_DEMO = "com.foldduo.hinge.DEMO"
        private const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        private const val PANEL_STABLE_MS = 120L
        private const val PANEL_REATTACH_MS = 16L
        private const val COVER_CAPTURE_DELAY_MS = 80L
        private const val MAX_CAPTURE_ATTEMPTS = 3
        private const val MAX_COVER_CAPTURE_ATTEMPTS = 6
        private const val CAPTURE_TIMEOUT_MS = 800L
        private const val BLACK_THRESHOLD = 30
        private const val REST_LEAVE_TILT = 3f
        private const val FADE_OUT_FLAT_MS = 120L
        private const val COVER_PREPARE_HINGE = 150f
        private const val COVER_PREPARE_DONE_HINGE = 1.5f
        private const val DUAL_RELEASE_OPEN_HINGE = 170f
        private const val OPENING_INNER_ARM_HINGE = 2f
        private const val OPENING_INNER_WAKE_HINGE = 3f
        private const val OPENING_INNER_RELEASE_HINGE = 90f
        private const val OPENING_INNER_DISPLAY_RETRY_MS = 16L
        private const val OPENING_CATCH_UP_MIN_DEGREES = 12f
        private const val OPENING_CATCH_UP_VISIBLE_MS = 180L
        private const val CLOSED_CHECK_INTERVAL_MS = 80L
        private const val CLOSED_HANDOFF_HOLD_MS = 450L
        private const val CLOSED_HANDOFF_RETRY_MS = 50L
        private const val DETACH_RETRY_MS = 16L
        private const val MAX_DETACH_ATTEMPTS = 3
        private const val STATIONARY_TIMEOUT_MS = 1_000L
        private const val STATIONARY_ANGLE_TOLERANCE = 1.5f
        private const val CONCURRENT_COVER_DISPLAY_ID = 1
        private val createHardwareBitmap = lazy {
            Class.forName("android.graphics.HardwareRenderer").getDeclaredMethod(
                "createHardwareBitmap",
                RenderNode::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }

        @Volatile
        var instance: HingeOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, HingeOverlayService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    info.resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) } == self
                }
        }
    }
}
