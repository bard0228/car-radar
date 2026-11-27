package tw.bard.carradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
 * Full-screen FLAG_NOT_TOUCHABLE hardware-accelerated canvas for the Floating Liquid Water-Ball.
 * Because this window NEVER moves and NEVER resizes (always 0,0 MATCH_PARENT),
 * SurfaceFlinger has ZERO 1-frame position jumps during idle <-> expanded or edge-snap transitions.
 * Touch events are handled by the tightly-sized invisible proxy window in OverlayController,
 * using the exact same fitInsetsTypes=0 screen coordinate system.
 */
class LiquidOrbView(
    context: Context,
    val physics: LiquidOrbPhysics
) : View(context) {

    var speedKmh: Int = 0
    var activeHud: ActiveCameraHud? = null
    private var lastDisplayHud: ActiveCameraHud? = null

    private var dismissAlpha: Float = 0f
    private var dismissScale: Float = 1f
    private val screenLoc = IntArray(2)

    private val jakartaBold: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.jakarta_bold)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private val tmpRect = RectF()
    private val sheenRect = RectF()
    private val unifiedPath = Path()
    private val bridgePath = Path()

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

    // Bottom X Dismiss Target Paints
    private val dismissBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dismissStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val dismissCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
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

        getLocationOnScreen(screenLoc)
        val originX = screenLoc[0].toFloat()
        val originY = screenLoc[1].toFloat()

        // 0. Draw Bottom 'X' Dismiss Target smoothly when dragging detached water-ball
        val targetDismissAlpha = if (physics.shouldShowDismissTarget()) 1f else 0f
        dismissAlpha += (targetDismissAlpha - dismissAlpha) * 0.25f
        if (dismissAlpha > 0.02f) {
            val targetScale = if (physics.isHoveringDismiss) 1.18f else 1.0f
            dismissScale += (targetScale - dismissScale) * 0.25f
            drawDismissTarget(canvas, originX, originY)
        } else {
            dismissAlpha = 0f
        }

        val r = physics.currentRadiusPx()
        val morph = physics.morphProgress.coerceIn(0f, 1f)

        val cx = physics.centerX - originX
        val cy = physics.centerY - originY

        val hudForStyle = activeHud ?: if (morph > 0.02f) lastDisplayHud else null
        val isOverspeed = hudForStyle != null &&
            hudForStyle.camera.limitKmh > 0 &&
            speedKmh > hudForStyle.camera.limitKmh

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

        // 2. Unified Liquid Glass Shell (when peeling, union orb + bridge into ONE seamless body!)
        val isPeelingBridge = physics.isPeelingFromEdge && physics.peelStretchPx > 1f
        if (isPeelingBridge) {
            val rx = r * scaleMajor
            val ry = r * scaleMinor
            tmpRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            unifiedPath.reset()
            unifiedPath.addOval(tmpRect, Path.Direction.CW)

            val ratio = (physics.peelStretchPx / physics.snapOffPx).coerceIn(0f, 1f)
            val isLeft = physics.snappedSide == OrbSide.LEFT
            val wallX = (if (isLeft) 0f else physics.bounds.screenW) - originX
            val footHalfH = ry * (0.92f - 0.50f * ratio)
            val neckPinchY = ry * (0.52f - 0.38f * ratio)
            val midX = (wallX + cx) * 0.5f

            bridgePath.reset()
            if (isLeft) {
                bridgePath.moveTo(wallX, cy - footHalfH)
                bridgePath.quadTo(midX, cy - neckPinchY, cx, cy - ry * 0.78f)
                bridgePath.lineTo(cx, cy + ry * 0.78f)
                bridgePath.quadTo(midX, cy + neckPinchY, wallX, cy + footHalfH)
            } else {
                bridgePath.moveTo(cx, cy - ry * 0.78f)
                bridgePath.quadTo(midX, cy - neckPinchY, wallX, cy - footHalfH)
                bridgePath.lineTo(wallX, cy + footHalfH)
                bridgePath.quadTo(midX, cy + neckPinchY, cx, cy + ry * 0.78f)
            }
            bridgePath.close()

            unifiedPath.op(bridgePath, Path.Op.UNION)

            val gradLeft = minOf(wallX, cx - rx)
            val gradRight = maxOf(wallX, cx + rx)
            fillPaint.shader = LinearGradient(
                gradLeft,
                cy - ry,
                gradRight,
                cy + ry,
                if (isOverspeed) Color.parseColor("#FCFFF4F4") else Color.parseColor("#FAFFFFFF"),
                if (isOverspeed) Color.parseColor("#F0FDE2E2") else Color.parseColor("#EBEAF3EE"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(unifiedPath, fillPaint)

            sheenRect.set(cx - rx * 0.68f, cy - ry * 0.80f, cx + rx * 0.48f, cy - ry * 0.14f)
            sheenPaint.shader = LinearGradient(
                sheenRect.left, sheenRect.top, sheenRect.left, sheenRect.bottom,
                Color.parseColor("#C2FFFFFF"),
                Color.parseColor("#00FFFFFF"),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipPath(unifiedPath)
            canvas.drawOval(sheenRect, sheenPaint)
            canvas.restore()

            strokePaint.color = if (isOverspeed) {
                Color.parseColor("#E6C94A4A")
            } else {
                Color.parseColor("#FFFFFFFF")
            }
            canvas.drawPath(unifiedPath, strokePaint)
        } else {
            canvas.save()
            if (wobbleMag > 0.002f) {
                canvas.rotate(wobbleDeg, cx, cy)
                canvas.scale(scaleMajor, scaleMinor, cx, cy)
            }

            tmpRect.set(cx - r, cy - r, cx + r, cy + r)

            fillPaint.shader = LinearGradient(
                cx - r,
                cy - r,
                cx + r,
                cy + r,
                if (isOverspeed) Color.parseColor("#FCFFF4F4") else Color.parseColor("#FAFFFFFF"),
                if (isOverspeed) Color.parseColor("#F0FDE2E2") else Color.parseColor("#EBEAF3EE"),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(tmpRect, fillPaint)

            sheenRect.set(cx - r * 0.68f, cy - r * 0.80f, cx + r * 0.48f, cy - r * 0.14f)
            sheenPaint.shader = LinearGradient(
                sheenRect.left, sheenRect.top, sheenRect.left, sheenRect.bottom,
                Color.parseColor("#C2FFFFFF"),
                Color.parseColor("#00FFFFFF"),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(sheenRect, sheenPaint)

            strokePaint.color = if (isOverspeed) {
                Color.parseColor("#E6C94A4A")
            } else {
                Color.parseColor("#FFFFFFFF")
            }
            canvas.drawOval(tmpRect, strokePaint)

            canvas.restore()
        }

        // 3. Typography (Strictly 0° horizontal, Tabular numbers)
        drawTypography(canvas, cx, cy, r, morph, hudForStyle, isOverspeed)
    }

    private fun drawDismissTarget(canvas: Canvas, originX: Float, originY: Float) {
        val dcx = physics.dismissCenterX() - originX
        val dcy = physics.dismissCenterY() - originY + dp(14f) * (1f - dismissAlpha)
        val r = dp(28f) * dismissScale

        val baseBg = if (physics.isHoveringDismiss) Color.parseColor("#F0C94A4A") else Color.parseColor("#E61E293B")
        dismissBgPaint.color = baseBg
        dismissBgPaint.alpha = (Color.alpha(baseBg) * dismissAlpha).toInt()

        dismissStrokePaint.color = Color.WHITE
        dismissStrokePaint.alpha = (230 * dismissAlpha).toInt()

        dismissCrossPaint.alpha = (255 * dismissAlpha).toInt()

        canvas.drawCircle(dcx, dcy, r, dismissBgPaint)
        canvas.drawCircle(dcx, dcy, r, dismissStrokePaint)

        val arm = dp(9f) * dismissScale
        canvas.drawLine(dcx - arm, dcy - arm, dcx + arm, dcy + arm, dismissCrossPaint)
        canvas.drawLine(dcx + arm, dcy - arm, dcx - arm, dcy + arm, dismissCrossPaint)
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
        speedPaint.textSize = sp(26f - 5f * morph)
        speedPaint.color = if (isOverspeed) Color.parseColor("#C94A4A") else Color.parseColor("#1E293B")
        val speedCenterY = cy - r * 0.48f * morph
        drawCenteredText(canvas, speedKmh.toString(), cx, speedCenterY, speedPaint)

        if (morph > 0.12f && hud != null) {
            val alphaFloat = ((morph - 0.12f) / 0.88f).coerceIn(0f, 1f)
            val alphaInt = (alphaFloat * 255).toInt()

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

            val label = hud.camera.displayLabel()
            val isNumeric = hud.camera.limitKmh > 0
            limitPaint.textSize = if (isNumeric) sp(32f) else sp(16.5f)
            limitPaint.color = when {
                isNumeric -> Color.parseColor("#C94A4A")
                hud.camera.kind == CameraKind.RED_LIGHT -> Color.parseColor("#C94A4A")
                else -> Color.parseColor("#4A7461")
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
