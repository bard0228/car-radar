package tw.bard.carradar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.data.OverlayDisplayMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.ui.MainActivity
import kotlin.math.abs

class OverlayController(
    private val context: Context,
    private val prefs: Prefs
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: LinearLayout? = null
    private var speedText: TextView? = null
    private var badgeText: TextView? = null
    private var distText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun update(speedKmh: Int, hud: ActiveCameraHud?) {
        if (!prefs.overlayEnabled || !canDraw()) {
            hide()
            return
        }
        val shouldShow = when (prefs.overlayMode) {
            OverlayDisplayMode.ALWAYS -> true
            OverlayDisplayMode.APPROACH_ONLY -> hud != null
        }
        if (!shouldShow) {
            hide()
            return
        }
        ensureAttached()
        speedText?.text = speedKmh.toString()

        if (hud != null) {
            val cam = hud.camera
            badgeText?.visibility = View.VISIBLE
            distText?.visibility = View.VISIBLE
            distText?.text = "${hud.distanceM}m"

            val badge = badgeText
            if (badge != null) {
                badge.text = cam.displayLabel()
                val bg = GradientDrawable().apply {
                    shape = if (cam.limitKmh > 0) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                    if (cam.limitKmh <= 0) cornerRadius = dp(10f)
                    setColor(Color.WHITE)
                    val strokeColor = when {
                        cam.limitKmh > 0 -> Color.parseColor("#DC2626")
                        cam.kind == CameraKind.RED_LIGHT -> Color.parseColor("#DC2626")
                        else -> Color.parseColor("#3B6FD4")
                    }
                    setStroke(dp(3f).toInt(), strokeColor)
                }
                badge.background = bg
                badge.setTextColor(Color.parseColor("#1E293B"))
            }
        } else {
            badgeText?.visibility = View.GONE
            distText?.visibility = View.GONE
        }
    }

    fun hide() {
        val v = rootView ?: return
        runCatching { wm.removeView(v) }
        rootView = null
        speedText = null
        badgeText = null
        distText = null
        layoutParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAttached() {
        if (rootView != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            elevation = dp(8f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f)
                setColor(Color.parseColor("#E6F0F3F8"))
                setStroke(dp(1.2f).toInt(), Color.parseColor("#E6FFFFFF"))
            }
        }

        val jakartaBold = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, tw.bard.carradar.R.font.jakarta_bold)
        }.getOrNull() ?: Typeface.DEFAULT_BOLD

        val tvSpeed = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.parseColor("#1E293B"))
            typeface = jakartaBold
            fontFeatureSettings = "tnum"
            text = "0"
        }

        val tvUnit = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#64748B"))
            text = " km/h"
            setPadding(0, dp(6f).toInt(), dp(8f).toInt(), 0)
        }

        val tvBadge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = jakartaBold
            gravity = Gravity.CENTER
            minWidth = dp(38f).toInt()
            minHeight = dp(38f).toInt()
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            fontFeatureSettings = "tnum"
            visibility = View.GONE
        }

        val tvDist = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#D97736"))
            typeface = jakartaBold
            fontFeatureSettings = "tnum"
            setPadding(dp(8f).toInt(), 0, 0, 0)
            visibility = View.GONE
        }

        container.addView(tvSpeed)
        container.addView(tvUnit)
        container.addView(tvBadge)
        container.addView(tvDist)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX
            y = prefs.overlayY
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    } else {
                        val i = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(i)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            rootView = container
            speedText = tvSpeed
            badgeText = tvBadge
            distText = tvDist
            layoutParams = params
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
