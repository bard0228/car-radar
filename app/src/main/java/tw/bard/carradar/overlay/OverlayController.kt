package tw.bard.carradar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.data.OverlayDisplayMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.ui.MainActivity
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Dual-Window Zero-Jitter Overlay Architecture:
 * 1. Visual Window (`orbView`): Full-screen MATCH_PARENT + FLAG_NOT_TOUCHABLE + FLAG_KEEP_SCREEN_ON.
 *    Never moves, never resizes -> 100% eliminates all SurfaceFlinger 1-frame position jumps!
 * 2. Touch Proxy Window (`touchProxyView`): Invisible transparent hitbox sized tightly to the
 *    current orb diameter (small orb = small hitbox, large orb = large hitbox), so nearby desktop
 *    app icons are never accidentally blocked.
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var orbView: LiquidOrbView? = null
    private var touchProxyView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var physics: LiquidOrbPhysics? = null
    private var velocityTracker: VelocityTracker? = null

    private var lastFrameMs: Long = 0L
    private var lastMoveMs: Long = 0L
    private var isLoopRunning: Boolean = false
    private var lastHud: ActiveCameraHud? = null
    private var lastSpeed: Int = 0
    private var pendingHideAfterRetract: Boolean = false

    @Volatile
    var isTemporarilyDismissed: Boolean = false
        private set

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
            view.invalidate()

            if (stillAnimating) {
                view.postOnAnimation(this)
            } else {
                isLoopRunning = false
                lastFrameMs = 0L
                prefs.overlaySide = phys.snappedSide.name
                prefs.overlayY = phys.centerY.toInt()
                // Sync invisible touch proxy to exact settled orb size & position
                syncTouchProxy(phys)
                if (!phys.isAlertActive && phys.morphProgress <= 0.01f && pendingHideAfterRetract) {
                    pendingHideAfterRetract = false
                    hide()
                }
            }
        }
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun restoreFromForeground() {
        if (isTemporarilyDismissed) {
            isTemporarilyDismissed = false
            update(lastSpeed, lastHud)
        }
    }

    fun update(speedKmh: Int, hud: ActiveCameraHud?) {
        lastSpeed = speedKmh
        lastHud = hud

        if (isTemporarilyDismissed || !prefs.overlayEnabled || !canDraw()) {
            hide()
            return
        }

        val mode = prefs.overlayMode
        if (mode == OverlayDisplayMode.APPROACH_ONLY) {
            if (hud != null) {
                pendingHideAfterRetract = false
                ensureAttached()
                orbView?.updateHud(speedKmh, hud)
                physics?.let { syncTouchProxy(it) }
                startAnimationLoop()
            } else if (orbView != null) {
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
        physics?.let { syncTouchProxy(it) }
        startAnimationLoop()
    }

    fun hide() {
        isLoopRunning = false
        pendingHideAfterRetract = false
        orbView?.let { v ->
            v.removeCallbacks(frameRunnable)
            runCatching { wm.removeView(v) }
        }
        touchProxyView?.let { tv ->
            runCatching { wm.removeView(tv) }
        }
        orbView = null
        touchProxyView = null
        touchParams = null
        physics = null
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

        // 1. Full-screen visual canvas (NOT touchable -> 100% pass-through, NEVER resizes, ZERO jitter!)
        val visualView = LiquidOrbView(context, phys)
        visualView.speedKmh = lastSpeed
        visualView.activeHud = lastHud

        val visualParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // 2. Invisible tight touch proxy window (only covers the actual orb area)
        val proxyView = View(context)
        val tParams = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        proxyView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    phys.updateBounds(computeSafeBounds())
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    lastMoveMs = SystemClock.uptimeMillis()
                    phys.onTouchDown(ev.rawX, ev.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val now = SystemClock.uptimeMillis()
                    val dt = ((now - lastMoveMs) / 1000f).coerceIn(0.004f, 0.05f)
                    lastMoveMs = now
                    phys.onTouchMove(ev.rawX, ev.rawY, dt)

                    if (phys.consumeSnapOffHaptic()) {
                        proxyView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (phys.consumeDismissHoverHaptic()) {
                        proxyView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    visualView.invalidate()
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

                    val result = phys.onTouchUp(vx, vy)
                    if (result.dismissed) {
                        isTemporarilyDismissed = true
                        hide()
                    } else if (result.wasTap) {
                        val i = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(i)
                    } else {
                        startAnimationLoop()
                    }
                    true
                }
                else -> false
            }
        }

        physics = phys
        orbView = visualView
        touchProxyView = proxyView
        touchParams = tParams

        applyTouchProxyBounds(phys, tParams)
        runCatching {
            wm.addView(visualView, visualParams)
            wm.addView(proxyView, tParams)
        }.onFailure {
            hide()
        }
    }

    private fun syncTouchProxy(phys: LiquidOrbPhysics) {
        val pv = touchProxyView ?: return
        val tp = touchParams ?: return
        val oldX = tp.x
        val oldY = tp.y
        val oldW = tp.width
        val oldH = tp.height
        applyTouchProxyBounds(phys, tp)
        if (tp.x != oldX || tp.y != oldY || tp.width != oldW || tp.height != oldH) {
            runCatching { wm.updateViewLayout(pv, tp) }
        }
    }

    private fun applyTouchProxyBounds(phys: LiquidOrbPhysics, tp: WindowManager.LayoutParams) {
        val r = phys.currentRadiusPx()
        val sizePx = ceil(r * 2f + 4f * phys.density).toInt()
        val maxX = (phys.bounds.screenW - sizePx).coerceAtLeast(0f)
        val maxY = (phys.bounds.screenH - sizePx).coerceAtLeast(0f)
        tp.width = sizePx
        tp.height = sizePx
        tp.x = (phys.centerX - sizePx * 0.5f).coerceIn(0f, maxX).roundToInt()
        tp.y = (phys.centerY - sizePx * 0.5f).coerceIn(0f, maxY).roundToInt()
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
