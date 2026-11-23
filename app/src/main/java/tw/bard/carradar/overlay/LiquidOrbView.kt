package tw.bard.carradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import tw.bard.carradar.R
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.core.CameraKind
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Ultra-smooth Liquid Glass Water-Ball View:
 * - Zero polygon/Path.op artifacts: 100% pure hardware-antialiased ovals & gradients.
 * - Subtle jelly squash & stretch when dragged or snapping to edge.
 * - Typography hierarchy: Bottom speed limit (32sp) > Top current speed (21sp),
 *   Middle remaining distance (14.5sp bold, dark slate #334155) with crisp divider lines.
 */
class LiquidOrbView(
    context: Context,
    val physics: LiquidOrbPhysics
) : View(context) {

    var windowOriginX: Float = 0f
    var windowOriginY: Float = 0f

    var speedKmh: Int = 0
    var activeHud: ActiveCameraHud? = null
    private var lastDisplayHud: ActiveCameraHud? = null

    private val jakartaBold: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.jakarta_bold)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private val tmpRect = RectF()
    private val sheenRect = RectF()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = jakartaBold
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        color = Color.parseColor("#1E293B")
    }

    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = jakartaBold
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        color = Color.parseColor("#334155")
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#80475569")
    }

    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = jakartaBold
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        color = Color.parseColor("#C94A4A")
    }

    fun updateHud(speed: Int, hud: ActiveCameraHud?) {
        speedKmh = speed
        activeHud = hud
        if (hud != null) {
            lastDisplayHud = hud
        }
        physics.setAlertActive(hud != null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val r = physics.currentRadiusPx()
        val morph = physics.morphProgress.coerceIn(0f, 1f)

        val cx = physics.centerX - windowOriginX
        val cy = physics.centerY - windowOriginY

        val hudForStyle = activeHud ?: if (morph > 0.02f) lastDisplayHud else null
        val isOverspeed = hudForStyle != null &&
            hudForStyle.camera.limitKmh > 0 &&
            speedKmh > hudForStyle.camera.limitKmh

        // Compute subtle volume-preserving jelly squash & stretch along wobble vector
        val wobbleMag = hypot(physics.wobbleX, physics.wobbleY)
        val scaleMajor = 1f + wobbleMag
        val scaleMinor = 1f / sqrt(scaleMajor)
        val wobbleDeg = if (wobbleMag > 0.002f) {
            Math.toDegrees(atan2(physics.wobbleY.toDouble(), physics.wobbleX.toDouble())).toFloat()
        } else {
            0f
        }

        // 1. Soft Ambient Shadow Halo
        val shadowRadius = r * 1.18f
        shadowPaint.shader = RadialGradient(
            cx,
            cy + dp(2.5f),
            shadowRadius,
            intArrayOf(
                if (isOverspeed) Color.parseColor("#3CC94A4A") else Color.parseColor("#281E293B"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + dp(2.5f), shadowRadius, shadowPaint)

        // 2. Smooth Water-Ball Shell (rotated & gently squashed/stretched, 100% pure oval)
        canvas.save()
        if (wobbleMag > 0.002f) {
            canvas.rotate(wobbleDeg, cx, cy)
            canvas.scale(scaleMajor, scaleMinor, cx, cy)
        }

        tmpRect.set(cx - r, cy - r, cx + r, cy + r)

        // Liquid Glass Frosted Fill
        fillPaint.shader = LinearGradient(
            cx - r,
            cy - r,
            cx + r,
            cy + r,
            if (isOverspeed) Color.parseColor("#F0FFF4F4") else Color.parseColor("#ECFFFFFF"),
            if (isOverspeed) Color.parseColor("#DDFDE2E2") else Color.parseColor("#D6E9EFF8"),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(tmpRect, fillPaint)

        // Upper-left Liquid Glass Specular Sheen
        sheenRect.set(cx - r * 0.68f, cy - r * 0.80f, cx + r * 0.48f, cy - r * 0.14f)
        sheenPaint.shader = LinearGradient(
            sheenRect.left, sheenRect.top, sheenRect.left, sheenRect.bottom,
            Color.parseColor("#9EFFFFFF"),
            Color.parseColor("#00FFFFFF"),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(sheenRect, sheenPaint)

        // Crisp Glass Border Rim
        strokePaint.color = if (isOverspeed) {
            Color.parseColor("#D9C94A4A")
        } else {
            Color.parseColor("#F7FFFFFF")
        }
        canvas.drawOval(tmpRect, strokePaint)

        canvas.restore()

        // 3. Typography (Strictly 0° horizontal, Tabular numbers, anchored at center)
        drawTypography(canvas, cx, cy, r, morph, hudForStyle, isOverspeed)
    }

    private fun drawTypography(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        morph: Float,
        hud: ActiveCameraHud?,
        isOverspeed: Boolean
    ) {
        // 1. Top: Current Speed (26sp centered in Idle -> shrinks to 21sp on top in Alert Expanded so Limit is larger)
        speedPaint.textSize = sp(26f - 5f * morph)
        speedPaint.color = if (isOverspeed) Color.parseColor("#C94A4A") else Color.parseColor("#1E293B")
        val speedCenterY = cy - r * 0.48f * morph
        drawCenteredText(canvas, speedKmh.toString(), cx, speedCenterY, speedPaint)

        // 2. Middle Distance Divider & Bottom Speed Limit (fade in smoothly with morphProgress)
        if (morph > 0.12f && hud != null) {
            val alphaFloat = ((morph - 0.12f) / 0.88f).coerceIn(0f, 1f)
            val alphaInt = (alphaFloat * 255).toInt()

            // Middle: Larger & Darker Remaining Distance (14.5sp Bold, #334155) + crisp divider lines
            val distText = "${hud.distanceM}m"
            val distCenterY = cy - r * 0.04f
            distPaint.textSize = sp(14.5f)
            distPaint.alpha = (alphaFloat * 245).toInt()
            drawCenteredText(canvas, distText, cx, distCenterY, distPaint)

            val textHalfW = distPaint.measureText(distText) * 0.5f + dp(5f)
            val lineOuterX = r * 0.80f
            if (lineOuterX > textHalfW + dp(3f)) {
                dividerPaint.alpha = (alphaFloat * 145).toInt()
                canvas.drawLine(cx - lineOuterX, distCenterY, cx - textHalfW, distCenterY, dividerPaint)
                canvas.drawLine(cx + textHalfW, distCenterY, cx + lineOuterX, distCenterY, dividerPaint)
            }

            // Bottom: Speed Limit (32sp Bold — prominently LARGER than top current speed!)
            val label = hud.camera.displayLabel()
            val isNumeric = hud.camera.limitKmh > 0
            limitPaint.textSize = if (isNumeric) sp(32f) else sp(16.5f)
            limitPaint.color = when {
                isNumeric -> Color.parseColor("#C94A4A")
                hud.camera.kind == CameraKind.RED_LIGHT -> Color.parseColor("#C94A4A")
                else -> Color.parseColor("#D97736")
            }
            limitPaint.alpha = alphaInt
            val limitCenterY = cy + r * (0.44f - 0.06f * (1f - alphaFloat))
            drawCenteredText(canvas, label, cx, limitCenterY, limitPaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
