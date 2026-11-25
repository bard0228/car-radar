package tw.bard.carradar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
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
 * High-FPS WindowManager host for the Floating Liquid Water-Ball:
 * - Strictly clamps window x/y within [0, screenW - w] (NO FLAG_LAYOUT_NO_LIMITS) so the orb
 *   NEVER sneaks into screen edges during small <-> large transitions.
 * - Uses FLAG_KEEP_SCREEN_ON while visible so screen never times out while driving.
 * - Hosts a Liquid Glass bottom 'X' dismiss target while dragging; dropping inside hides
 *   the orb until the user re-enters the app foreground.
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

    // Bottom X dismiss target window
    private var dismissView: DismissTargetView? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    private var lastFrameMs: Long = 0L
    private var lastMoveMs: Long = 0L
    private var isLoopRunning: Boolean = false
    private var lastHud: ActiveCameraHud? = null
    private var lastSpeed: Int = 0
    private var pendingHideAfterRetract: Boolean = false
    private var isTightIdleWindow: Boolean = false

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
            syncWindowPositionOnly(view, phys)
            view.invalidate()

            if (stillAnimating) {
                view.postOnAnimation(this)
            } else {
                isLoopRunning = false
                lastFrameMs = 0L
                prefs.overlaySide = phys.snappedSide.name
                prefs.overlayY = phys.centerY.toInt()
                if (!phys.isAlertActive && phys.morphProgress <= 0.01f) {
                    if (pendingHideAfterRetract) {
                        pendingHideAfterRetract = false
                        hide()
                        return
                    }
                    // Shrink touchable window to match small Idle Orb so nearby home screen icons remain clickable
                    setWindowSizeMode(view, phys, tightIdle = true)
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
        hideDismissTarget()
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
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        view.setOnTouchListener { _, ev ->
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

                    // Only expand window when user actually starts dragging/peeling (never on a simple tap!)
                    if (isTightIdleWindow && (phys.peelStretchPx > 0.5f || !phys.isPeelingFromEdge)) {
                        setWindowSizeMode(view, phys, tightIdle = false)
                    }

                    if (phys.consumeSnapOffHaptic()) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (phys.consumeDismissHoverHaptic()) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    if (phys.shouldShowDismissTarget()) {
                        showOrUpdateDismissTarget(phys)
                    } else {
                        hideDismissTarget()
                    }

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

                    hideDismissTarget()
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
        orbView = view
        layoutParams = params

        applyClampedWindowPosition(view, phys, params)
        runCatching {
            wm.addView(view, params)
        }.onFailure {
            orbView = null
            physics = null
            layoutParams = null
        }
    }

    private fun showOrUpdateDismissTarget(phys: LiquidOrbPhysics) {
        val d = phys.density
        val sizePx = ceil(84f * d).toInt()
        if (dismissView == null) {
            val dv = DismissTargetView(context)
            val dp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (phys.dismissCenterX() - sizePx * 0.5f).roundToInt()
                y = (phys.dismissCenterY() - sizePx * 0.5f).roundToInt()
            }
            runCatching {
                wm.addView(dv, dp)
                dismissView = dv
                dismissParams = dp
                dv.alpha = 0f
                dv.translationY = 20f * d
                dv.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
        }
        dismissView?.setActiveHover(phys.isHoveringDismiss)
    }

    private fun hideDismissTarget() {
        val dv = dismissView ?: return
        dismissView = null
        dismissParams = null
        runCatching { wm.removeView(dv) }
    }

    private fun windowSizePx(phys: LiquidOrbPhysics, tightIdle: Boolean): Int {
        if (tightIdle) {
            // Exact small orb diameter + minimal 4dp rim so nearby desktop icons are never blocked
            return ceil(phys.idleRadiusPx * 2f + 8f * phys.density).toInt()
        }
        val pad = 12f * phys.density
        val activeDia = phys.expandedRadiusPx * 2f + phys.snapOffPx * 0.45f
        return ceil(activeDia + pad * 2f).toInt()
    }

    private fun setWindowSizeMode(view: LiquidOrbView, phys: LiquidOrbPhysics, tightIdle: Boolean) {
        val params = layoutParams ?: return
        if (isTightIdleWindow == tightIdle) return
        isTightIdleWindow = tightIdle
        val sizePx = windowSizePx(phys, tightIdle)
        params.width = sizePx
        params.height = sizePx
        applyClampedWindowPosition(view, phys, params)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun syncWindowPositionOnly(view: LiquidOrbView, phys: LiquidOrbPhysics) {
        val params = layoutParams ?: return
        val oldX = params.x
        val oldY = params.y
        applyClampedWindowPosition(view, phys, params)
        if (params.x != oldX || params.y != oldY) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    /**
     * Strictly clamps window origin within [0, screenW - width] and [0, screenH - height].
     * Guarantees windowOriginX/Y matches Android WindowManager 100% with zero edge drift!
     */
    private fun applyClampedWindowPosition(
        view: LiquidOrbView,
        phys: LiquidOrbPhysics,
        params: WindowManager.LayoutParams
    ) {
        val halfW = params.width * 0.5f
        val halfH = params.height * 0.5f
        val maxWinX = (phys.bounds.screenW - params.width).coerceAtLeast(0f)
        val maxWinY = (phys.bounds.screenH - params.height).coerceAtLeast(0f)

        val winX = (phys.centerX - halfW).coerceIn(0f, maxWinX).roundToInt()
        val winY = (phys.centerY - halfH).coerceIn(0f, maxWinY).roundToInt()

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

/**
 * Liquid Glass bottom 'X' dismiss target rendered while dragging the floating water-ball.
 */
private class DismissTargetView(context: Context) : View(context) {
    private var hovered = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
    }

    fun setActiveHover(isHovered: Boolean) {
        if (hovered == isHovered) return
        hovered = isHovered
        val targetScale = if (isHovered) 1.18f else 1.0f
        animate().scaleX(targetScale).scaleY(targetScale).setDuration(140L).start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = dp(28f)

        bgPaint.color = if (hovered) Color.parseColor("#E6C94A4A") else Color.parseColor("#D91E293B")
        strokePaint.color = if (hovered) Color.parseColor("#FFFFFFFF") else Color.parseColor("#CCFFFFFF")
        crossPaint.color = Color.WHITE

        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)

        val arm = dp(9f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
