package tw.bard.carradar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowManager
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.data.OverlayDisplayMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.ui.MainActivity
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Manages the WindowManager host for the Floating Liquid Orb.
 * Syncs LiquidOrbPhysics (screen-space single source of truth) into a tightly bounded
 * WindowManager layout so it never clips shadows/bridges/teardrops and never swallows
 * unrelated taps on apps underneath.
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var orbView: LiquidOrbView? = null
    private var physics: LiquidOrbPhysics? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var velocityTracker: VelocityTracker? = null

    private var lastFrameMs: Long = 0L
    private var lastMoveMs: Long = 0L
    private var isLoopRunning: Boolean = false
    private var lastHud: ActiveCameraHud? = null
    private var lastSpeed: Int = 0
    private var pendingHideAfterRetract: Boolean = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val view = orbView ?: run {
                isLoopRunning = false
                return
            }
            val phys = physics ?: run {
                isLoopRunning = false
                return
            }

            val now = SystemClock.uptimeMillis()
            val dtSec = if (lastFrameMs > 0L) ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.033f) else 0.016f
            lastFrameMs = now

            val stillAnimating = phys.step(dtSec)
            if (phys.consumeSnapOffHaptic()) {
                view.performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }

            syncWindowToPhysics(view, phys, force = false)
            view.invalidate()

            if (stillAnimating) {
                view.postOnAnimation(this)
            } else {
                isLoopRunning = false
                lastFrameMs = 0L
                // Persist settled side & Y
                prefs.overlaySide = phys.snappedSide.name
                prefs.overlayY = phys.centerY.toInt()
                // Tighten window bounds once settled
                syncWindowToPhysics(view, phys, force = true)
                if (pendingHideAfterRetract && !phys.isAlertActive && phys.morphProgress <= 0.01f) {
                    pendingHideAfterRetract = false
                    hide()
                }
            }
        }
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun update(speedKmh: Int, hud: ActiveCameraHud?) {
        lastSpeed = speedKmh
        lastHud = hud

        if (!prefs.overlayEnabled || !canDraw()) {
            hide()
            return
        }

        val mode = prefs.overlayMode
        if (mode == OverlayDisplayMode.APPROACH_ONLY) {
            if (hud != null) {
                pendingHideAfterRetract = false
                ensureAttached()
                orbView?.updateHud(speedKmh, hud)
                startAnimationLoop()
            } else if (orbView != null) {
                // Smoothly retract to idle orb first, then hide
                pendingHideAfterRetract = true
                orbView?.updateHud(speedKmh, null)
                startAnimationLoop()
            }
            return
        }

        // OverlayDisplayMode.ALWAYS
        pendingHideAfterRetract = false
        ensureAttached()
        orbView?.updateHud(speedKmh, hud)
        startAnimationLoop()
    }

    fun hide() {
        isLoopRunning = false
        pendingHideAfterRetract = false
        val v = orbView ?: return
        v.removeCallbacks(frameRunnable)
        runCatching { wm.removeView(v) }
        orbView = null
        physics = null
        layoutParams = null
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun startAnimationLoop() {
        val v = orbView ?: return
        if (!isLoopRunning) {
            isLoopRunning = true
            lastFrameMs = SystemClock.uptimeMillis()
            v.postOnAnimation(frameRunnable)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAttached() {
        if (orbView != null) return

        val density = context.resources.displayMetrics.density
        val bounds = computeSafeBounds()
        val savedSide = if (prefs.overlaySide == OrbSide.LEFT.name) OrbSide.LEFT else OrbSide.RIGHT
        val savedY = prefs.overlayY.toFloat()

        val phys = LiquidOrbPhysics(
            density = density,
            bounds = bounds,
            initialSide = savedSide,
            initialCenterY = savedY
        )
        val view = LiquidOrbView(context, phys)
        view.speedKmh = lastSpeed
        view.activeHud = lastHud

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    phys.updateBounds(computeSafeBounds())
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    lastMoveMs = SystemClock.uptimeMillis()
                    phys.onTouchDown(ev.rawX, ev.rawY)
                    startAnimationLoop()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val now = SystemClock.uptimeMillis()
                    val dt = ((now - lastMoveMs) / 1000f).coerceIn(0.004f, 0.05f)
                    lastMoveMs = now
                    phys.onTouchMove(ev.rawX, ev.rawY, dt)
                    if (phys.consumeSnapOffHaptic()) {
                        view.performHapticFeedback(
                            HapticFeedbackConstants.CLOCK_TICK,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                        )
                    }
                    syncWindowToPhysics(view, phys, force = false)
                    view.invalidate()
                    startAnimationLoop()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(ev)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: phys.velX
                    val vy = velocityTracker?.yVelocity ?: phys.velY
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val wasTap = phys.onTouchUp(ev.rawX, ev.rawY, vx, vy)
                    if (wasTap) {
                        val i = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(i)
                    }
                    startAnimationLoop()
                    true
                }
                else -> false
            }
        }

        physics = phys
        orbView = view
        layoutParams = params

        computeWindowRect(phys, params, view)
        runCatching {
            wm.addView(view, params)
        }.onFailure {
            orbView = null
            physics = null
            layoutParams = null
        }
    }

    private fun syncWindowToPhysics(view: LiquidOrbView, phys: LiquidOrbPhysics, force: Boolean) {
        val params = layoutParams ?: return
        val oldX = params.x
        val oldY = params.y
        val oldW = params.width
        val oldH = params.height

        computeWindowRect(phys, params, view)
        if (force || params.x != oldX || params.y != oldY || params.width != oldW || params.height != oldH) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun computeWindowRect(
        phys: LiquidOrbPhysics,
        params: WindowManager.LayoutParams,
        view: LiquidOrbView
    ) {
        val d = phys.density
        val r = phys.currentRadiusPx()
        val shadowPad = 14f * d
        val tearPad = if (phys.isDragging || phys.teardropFactor > 0.02f) (phys.maxSwayPx + phys.gravityDropPx + 12f * d) else 0f
        val peelPadLeft = if (phys.isPeelingFromEdge && phys.snappedSide == OrbSide.LEFT) phys.peelStretchPx + 12f * d else 0f
        val peelPadRight = if (phys.isPeelingFromEdge && phys.snappedSide == OrbSide.RIGHT) phys.peelStretchPx + 12f * d else 0f

        var left = phys.centerX - r - shadowPad - tearPad - peelPadLeft
        var right = phys.centerX + r + shadowPad + tearPad + peelPadRight
        var top = phys.centerY - r - shadowPad - tearPad
        var bottom = phys.centerY + r + shadowPad + tearPad

        // Keep window cleanly anchored to 0..screenW when snapped so edge bridge reaches x=0 / screenW
        if (left < 0f) left = 0f
        if (right > phys.bounds.screenW) right = phys.bounds.screenW
        if (top < 0f) top = 0f
        if (bottom > phys.bounds.screenH) bottom = phys.bounds.screenH

        val winX = floor(left).toInt()
        val winY = floor(top).toInt()
        val winW = ceil(right - winX).toInt().coerceAtLeast(1)
        val winH = ceil(bottom - winY).toInt().coerceAtLeast(1)

        params.x = winX
        params.y = winY
        params.width = winW
        params.height = winH

        view.windowOriginX = winX.toFloat()
        view.windowOriginY = winY.toFloat()
    }

    private fun computeSafeBounds(): ScreenSafeBounds {
        val dm = context.resources.displayMetrics
        val density = dm.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val b = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return ScreenSafeBounds(
                screenW = b.width().toFloat(),
                screenH = b.height().toFloat(),
                safeTop = insets.top.toFloat() + 8f * density,
                safeBottom = (b.height() - insets.bottom).toFloat() - 8f * density
            )
        }
        val statusBarH = getSystemDimen("status_bar_height", (28f * density).toInt())
        val navBarH = getSystemDimen("navigation_bar_height", (48f * density).toInt())
        return ScreenSafeBounds(
            screenW = dm.widthPixels.toFloat(),
            screenH = dm.heightPixels.toFloat(),
            safeTop = statusBarH + 8f * density,
            safeBottom = (dm.heightPixels - navBarH) - 8f * density
        )
    }

    private fun getSystemDimen(name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
    }
}
