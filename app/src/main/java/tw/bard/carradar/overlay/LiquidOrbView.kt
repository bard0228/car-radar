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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom Liquid Glass Orb View rendering:
 * - Idle Orb (circle, speed only, no unit)
 * - Direction-aware Alert Expanded Orb (droplet fusion from snapped edge, 3-layer tnum typography)
 * - Edge Peel Surface Tension Bridge (metaball neck stretching to 48dp snap-off)
 * - Dragging Teardrop (pointed top anchor + heavy pendulum bottom with squash & stretch, 0° locked text)
 */
class LiquidOrbView(
    context: Context,
    val physics: LiquidOrbPhysics
) : View(context) {

    var windowOriginX: Float = 0f
    var windowOriginY: Float = 0f

    var speedKmh: Int = 0
    var activeHud: ActiveCameraHud? = null
    // Cache last HUD so fade-out retraction keeps showing limit/distance smoothly until morphProgress hits 0
    private var lastDisplayHud: ActiveCameraHud? = null

    private val jakartaBold: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.jakarta_bold)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private val jakartaRegular: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.jakarta_regular)
    }.getOrNull() ?: Typeface.DEFAULT

    private val shellPath = Path()
    private val sheenPath = Path()
    private val tmpRect = RectF()

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
        typeface = jakartaRegular
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        color = Color.parseColor("#64748B")
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4464748B")
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
        val tear = physics.teardropFactor.coerceIn(0f, 1f)

        val localCx = physics.centerX - windowOriginX
        val localCy = physics.centerY - windowOriginY

        val hudForStyle = activeHud ?: if (morph > 0.02f) lastDisplayHud else null
        val isOverspeed = hudForStyle != null &&
            hudForStyle.camera.limitKmh > 0 &&
            speedKmh > hudForStyle.camera.limitKmh

        // Compute visual center of the heavy bulb (follows pendulum spring in teardrop mode)
        val bulbX = localCx + physics.massOffsetX * tear
        val bulbY = localCy + physics.massOffsetY * tear

        // 1. Soft Ambient Halo Shadow
        val shadowRadius = r * 1.24f
        shadowPaint.shader = RadialGradient(
            bulbX,
            bulbY + dp(3f),
            shadowRadius,
            intArrayOf(
                if (isOverspeed) Color.parseColor("#38C94A4A") else Color.parseColor("#2A1E293B"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bulbX, bulbY + dp(3f), shadowRadius, shadowPaint)

        // 2. Build Liquid Shell Path
        shellPath.reset()
        if (tear > 0.02f) {
            buildTeardropPath(shellPath, localCx, localCy, bulbX, bulbY, r, tear)
        } else {
            shellPath.addCircle(localCx, localCy, r, Path.Direction.CW)

            // 2a. Edge Peel Surface Tension Water Bridge
            if (physics.isPeelingFromEdge && physics.peelStretchPx > 1f) {
                addEdgePeelBridge(shellPath, localCx, localCy, r)
            }

            // 2b. Direction-Aware Alert Morph Secondary Droplet Fusion
            if (!physics.isDragging && morph in 0.02f..0.82f) {
                addMorphFusionDroplet(shellPath, localCx, localCy, r, morph)
            }
        }

        // 3. Liquid Glass Frosted Fill
        fillPaint.shader = LinearGradient(
            bulbX - r,
            bulbY - r,
            bulbX + r,
            bulbY + r,
            if (isOverspeed) Color.parseColor("#ECFFF5F5") else Color.parseColor("#E8FFFFFF"),
            if (isOverspeed) Color.parseColor("#D9FDE2E2") else Color.parseColor("#D0E9EFF8"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(shellPath, fillPaint)

        // 4. Top-left Crescent Liquid Glass Specular Sheen
        sheenPath.reset()
        tmpRect.set(bulbX - r * 0.72f, bulbY - r * 0.80f, bulbX + r * 0.50f, bulbY - r * 0.12f)
        sheenPath.addOval(tmpRect, Path.Direction.CW)
        sheenPaint.shader = LinearGradient(
            tmpRect.left, tmpRect.top, tmpRect.left, tmpRect.bottom,
            Color.parseColor("#99FFFFFF"),
            Color.parseColor("#00FFFFFF"),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(shellPath)
        canvas.drawPath(sheenPath, sheenPaint)
        canvas.restore()

        // 5. Crisp Liquid Glass Border Rim
        strokePaint.color = if (isOverspeed) {
            Color.parseColor("#CCCA4A4A")
        } else {
            Color.parseColor("#F5FFFFFF")
        }
        canvas.drawPath(shellPath, strokePaint)

        // 6. Typography (Strictly 0° horizontal, Tabular numbers, anchored at bulb center)
        drawTypography(canvas, bulbX, bulbY, r, morph, hudForStyle, isOverspeed)
    }

    /**
     * Smooth pointed-top heavy-bottom liquid teardrop with volume-preserving squash & stretch.
     */
    private fun buildTeardropPath(
        path: Path,
        anchorX: Float,
        anchorY: Float,
        bulbX: Float,
        bulbY: Float,
        r: Float,
        tear: Float
    ) {
        val disp = hypot(bulbX - anchorX, bulbY - anchorY)
        val normDisp = (disp / physics.maxSwayPx.coerceAtLeast(1f)).coerceIn(0f, 1.2f)
        val stretch = 1f + normDisp * 0.08f * tear
        val squash = 1f / sqrt(stretch)

        val rx = r * squash
        val ry = r * stretch

        // Top pointed apex stays near finger anchor
        val apexX = anchorX + (bulbX - anchorX) * (1f - tear) * 0.5f
        val apexY = anchorY - r * (0.35f + 0.55f * tear)

        // Vector from bulb center to apex
        val vx = apexX - bulbX
        val vy = apexY - bulbY
        val dist = hypot(vx, vy).coerceAtLeast(r * 1.05f)
        val baseAngle = atan2(vy, vx)

        // Tangent half-angle where neck smoothly meets the circular bottom bulb
        val halfSpread = (0.92f + 0.12f * (1f - tear)).toDouble()
        val leftAngle = baseAngle + halfSpread
        val rightAngle = baseAngle - halfSpread

        val leftTx = bulbX + (rx * cos(leftAngle)).toFloat()
        val leftTy = bulbY + (ry * sin(leftAngle)).toFloat()
        val rightTx = bulbX + (rx * cos(rightAngle)).toFloat()
        val rightTy = bulbY + (ry * sin(rightAngle)).toFloat()

        // Start at apex -> right shoulder -> bottom arc -> left shoulder -> back to apex
        val ux = vx / dist
        val uy = vy / dist
        val perpX = -uy
        val perpY = ux

        val ctrlNeckDist = dist * 0.38f
        val apexCtrlSpread = rx * 0.14f

        path.moveTo(apexX, apexY)
        // Right concave neck curve
        path.cubicTo(
            apexX + perpX * apexCtrlSpread - ux * (dist * 0.18f),
            apexY + perpY * apexCtrlSpread - uy * (dist * 0.18f),
            rightTx + ux * ctrlNeckDist * 0.55f,
            rightTy + uy * ctrlNeckDist * 0.55f,
            rightTx,
            rightTy
        )

        // Smooth bottom circular arc from rightAngle around to leftAngle
        val startDeg = Math.toDegrees(rightAngle).toFloat()
        var sweepDeg = Math.toDegrees(leftAngle - rightAngle).toFloat()
        // We want the LONG arc around the bottom of the droplet (positive sweep > 180 deg)
        if (sweepDeg <= 0f) sweepDeg += 360f
        if (sweepDeg < 180f) sweepDeg = 360f - sweepDeg
        // Determine direction: arc from rightAngle going AWAY from baseAngle
        val rightToLeftLong = -(360f - Math.toDegrees(2.0 * halfSpread).toFloat())
        tmpRect.set(bulbX - rx, bulbY - ry, bulbX + rx, bulbY + ry)
        path.arcTo(tmpRect, startDeg, rightToLeftLong, false)

        // Left concave neck curve back to apex
        path.cubicTo(
            leftTx + ux * ctrlNeckDist * 0.55f,
            leftTy + uy * ctrlNeckDist * 0.55f,
            apexX - perpX * apexCtrlSpread - ux * (dist * 0.18f),
            apexY - perpY * apexCtrlSpread - uy * (dist * 0.18f),
            apexX,
            apexY
        )
        path.close()
    }

    /**
     * Metaball-style surface tension bridge connecting the orb to the snapped screen edge.
     */
    private fun addEdgePeelBridge(path: Path, cx: Float, cy: Float, r: Float) {
        val ratio = (physics.peelStretchPx / physics.snapOffPx).coerceIn(0f, 1f)
        val isLeft = physics.snappedSide == OrbSide.LEFT
        val wallX = if (isLeft) -windowOriginX else (physics.bounds.screenW - windowOriginX)

        val footHalfH = r * (0.95f - 0.52f * ratio)
        val neckPinchY = r * (0.58f - 0.42f * ratio)
        val midX = (wallX + cx) * 0.5f
        val orbAttachX = cx + if (isLeft) -r * 0.25f else r * 0.25f

        val bridge = Path().apply {
            moveTo(wallX, cy - footHalfH)
            quadTo(midX, cy - neckPinchY, orbAttachX, cy - r * 0.78f)
            lineTo(orbAttachX, cy + r * 0.78f)
            quadTo(midX, cy + neckPinchY, wallX, cy + footHalfH)
            close()
        }
        path.op(bridge, Path.Op.UNION)
    }

    /**
     * Direction-aware secondary speed-limit droplet oozing out from the snapped edge and fusing into the orb.
     */
    private fun addMorphFusionDroplet(path: Path, cx: Float, cy: Float, r: Float, morph: Float) {
        val isLeft = physics.snappedSide == OrbSide.LEFT
        val wallX = if (isLeft) -windowOriginX else (physics.bounds.screenW - windowOriginX)
        val dir = if (isLeft) 1f else -1f

        // Droplet starts at edge and travels inward into main orb center
        val t = (morph / 0.82f).coerceIn(0f, 1f)
        val dropR = physics.idleRadiusPx * (0.36f + 0.24f * (1f - t))
        val dropCx = wallX + dir * (dropR * 0.6f + (absDist(cx - wallX) - dropR * 0.6f) * t)
        val dropCy = cy + r * 0.22f * (1f - t)

        val droplet = Path().apply {
            addCircle(dropCx, dropCy, dropR, Path.Direction.CW)
        }
        path.op(droplet, Path.Op.UNION)
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
        // 1. Speed Number (always visible, smoothly shifts upward during alert morph)
        speedPaint.textSize = sp(26f + 2f * morph)
        speedPaint.color = if (isOverspeed) Color.parseColor("#C94A4A") else Color.parseColor("#1E293B")
        val speedCenterY = cy - r * 0.43f * morph
        drawCenteredText(canvas, speedKmh.toString(), cx, speedCenterY, speedPaint)

        // 2. Middle Distance Divider & Bottom Limit (fade in smoothly with morphProgress)
        if (morph > 0.12f && hud != null) {
            val alphaFloat = ((morph - 0.12f) / 0.88f).coerceIn(0f, 1f)
            val alphaInt = (alphaFloat * 255).toInt()

            // Middle: Remaining Distance flanked by ultra-thin horizontal divider lines
            val distText = "${hud.distanceM}m"
            distPaint.textSize = sp(11.5f)
            distPaint.alpha = (alphaFloat * 210).toInt()
            drawCenteredText(canvas, distText, cx, cy, distPaint)

            val textHalfW = distPaint.measureText(distText) * 0.5f + dp(5f)
            val lineOuterX = r * 0.72f
            if (lineOuterX > textHalfW + dp(4f)) {
                dividerPaint.alpha = (alphaFloat * 85).toInt()
                canvas.drawLine(cx - lineOuterX, cy, cx - textHalfW, cy, dividerPaint)
                canvas.drawLine(cx + textHalfW, cy, cx + lineOuterX, cy, dividerPaint)
            }

            // Bottom: Speed Limit Number or Type Label
            val label = hud.camera.displayLabel()
            val isNumeric = hud.camera.limitKmh > 0
            limitPaint.textSize = if (isNumeric) sp(24f) else sp(14f)
            limitPaint.color = when {
                isNumeric -> Color.parseColor("#C94A4A")
                hud.camera.kind == CameraKind.RED_LIGHT -> Color.parseColor("#C94A4A")
                else -> Color.parseColor("#D97736")
            }
            limitPaint.alpha = alphaInt
            val limitCenterY = cy + r * (0.46f - 0.08f * (1f - alphaFloat))
            drawCenteredText(canvas, label, cx, limitCenterY, limitPaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun absDist(v: Float): Float = if (v < 0f) -v else v

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
