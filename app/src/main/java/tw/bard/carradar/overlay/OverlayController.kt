package tw.bard.carradar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowManager
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.data.OverlayDisplayMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.ui.MainActivity
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * High-FPS WindowManager host for the Floating Liquid Water-Ball.
 * Uses constant window width/height during drag & animation (updating ONLY x/y)
 * so SurfaceFlinger never reallocates the GPU buffer mid-drag -> instant 1:1 finger tracking!
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
    private var isTightIdleWindow: Boolean = false

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
            syncWindowPositionOnly(view, phys)
            view.invalidate()

            if (stillAnimating) {
                view.postOnAnimation(this)
            } else {
                isLoopRunning = false
                lastFrameMs = 0L
                prefs.overlaySide = phys.snappedSide.name
                prefs.overlayY = phys.centerY.toInt()
                // Tighten window size once settled in idle to minimize touch footprint
                if (!phys.isAlertActive && phys.morphProgress <= 0.01f) {
                    if (pendingHideAfterRetract) {
                        pendingHideAfterRetract = false
                        hide()
                        return
                    }
                    setWindowSizeMode(view, phys, tightIdle = true)
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
                val v = orbView ?: return
                val p = physics ?: return
                setWindowSizeMode(v, p, tightIdle = false)
                v.updateHud(speedKmh, hud)
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
        val v = orbView ?: return
        val p = physics ?: return
        if (hud != null) {
            setWindowSizeMode(v, p, tightIdle = false)
        }
        v.updateHud(speedKmh, hud)
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

        val startTight = (lastHud == null)
        val sizePx = windowSizePx(phys, startTight)
        isTightIdleWindow = startTight

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
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
                    // Expand window once on touch down if needed, keeping width/height fixed for entire drag!
                    if (isTightIdleWindow) {
                        setWindowSizeMode(view, phys, tightIdle = false)
                    }
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
                    // Pure x/y translation — zero buffer reallocation -> 1:1 instant finger tracking
                    syncWindowPositionOnly(view, phys)
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

                    val wasTap = phys.onTouchUp(vx, vy)
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

        applyCenteredWindowPosition(view, phys, params)
        runCatching {
            wm.addView(view, params)
        }.onFailure {
            orbView = null
            physics = null
            layoutParams = null
        }
    }

    private fun windowSizePx(phys: LiquidOrbPhysics, tightIdle: Boolean): Int {
        val pad = 18f * phys.density
        val baseDia = if (tightIdle) phys.idleRadiusPx * 2f else phys.expandedRadiusPx * 2f
        // Extra 16% room for subtle jelly wobble & soft shadow
        return ceil(baseDia * 1.16f + pad * 2f).toInt()
    }

    private fun setWindowSizeMode(view: LiquidOrbView, phys: LiquidOrbPhysics, tightIdle: Boolean) {
        val params = layoutParams ?: return
        if (isTightIdleWindow == tightIdle) return
        isTightIdleWindow = tightIdle
        val sizePx = windowSizePx(phys, tightIdle)
        params.width = sizePx
        params.height = sizePx
        applyCenteredWindowPosition(view, phys, params)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun syncWindowPositionOnly(view: LiquidOrbView, phys: LiquidOrbPhysics) {
        val params = layoutParams ?: return
        val oldX = params.x
        val oldY = params.y
        applyCenteredWindowPosition(view, phys, params)
        if (params.x != oldX || params.y != oldY) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun applyCenteredWindowPosition(
        view: LiquidOrbView,
        phys: LiquidOrbPhysics,
        params: WindowManager.LayoutParams
    ) {
        val halfW = params.width * 0.5f
        val halfH = params.height * 0.5f
        val winX = (phys.centerX - halfW).roundToInt()
        val winY = (phys.centerY - halfH).roundToInt()
        params.x = winX
        params.y = winY
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
