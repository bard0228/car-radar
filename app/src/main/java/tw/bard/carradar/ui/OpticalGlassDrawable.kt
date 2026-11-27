package tw.bard.carradar.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/**
 * Pure-visual Android native implementation of `.lg-optical` (Non-dispersive Optical Liquid Glass):
 * - 3-stop translucent diagonal glass body letting ambient sage-mist breathe through
 * - Top-left curved radial specular sheen (`::before`)
 * - 300° conic-gradient specular bezel rim (`::after`, 0% RGB color dispersion)
 * - Crisp 0.5dp outer cool hairline (`rgba(15, 23, 42, 0.10)`)
 */
class OpticalGlassDrawable(
    private val cornerRadiusPx: Float,
    private val density: Float,
    private val isPill: Boolean = false
) : Drawable() {

    private val rect = RectF()
    private val innerRect = RectF()
    private val clipPath = Path()
    private val rimMatrix = Matrix()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val conicRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * density
    }
    private val outerHairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.65f * density
        color = Color.parseColor("#1A0F172A")
    }

    private var currentRadius = cornerRadiusPx

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val halfStroke = outerHairlinePaint.strokeWidth * 0.5f
        rect.set(
            bounds.left + halfStroke,
            bounds.top + halfStroke,
            bounds.right - halfStroke,
            bounds.bottom - halfStroke
        )
        val rimInset = outerHairlinePaint.strokeWidth + conicRimPaint.strokeWidth * 0.5f
        innerRect.set(
            bounds.left + rimInset,
            bounds.top + rimInset,
            bounds.right - rimInset,
            bounds.bottom - rimInset
        )

        currentRadius = if (isPill) minOf(w, h) * 0.5f else cornerRadiusPx.coerceAtMost(minOf(w, h) * 0.5f)
        clipPath.reset()
        clipPath.addRoundRect(rect, currentRadius, currentRadius, Path.Direction.CW)

        // 1. 3-stop translucent optical glass body (155deg feel)
        bodyPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right * 0.85f,
            rect.bottom,
            intArrayOf(
                if (isPill) Color.parseColor("#C2FFFFFF") else Color.parseColor("#B0FFFFFF"),
                if (isPill) Color.parseColor("#7EFFFFFF") else Color.parseColor("#62FFFFFF"),
                if (isPill) Color.parseColor("#A4F0F7F3") else Color.parseColor("#8CF0F7F3")
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. Top-left curved specular sheen (::before radial light wash)
        val sheenRadius = maxOf(w, h) * 0.82f
        sheenPaint.shader = RadialGradient(
            rect.left + w * 0.14f,
            rect.top + h * 0.04f,
            sheenRadius.coerceAtLeast(1f),
            intArrayOf(
                Color.parseColor("#66FFFFFF"),
                Color.parseColor("#1EFFFFFF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.36f, 0.72f),
            Shader.TileMode.CLAMP
        )

        // 3. Soft upper bevel highlight band
        topGlowPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left,
            rect.top + h * 0.34f,
            Color.parseColor("#30FFFFFF"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        // 4. Conic specular bezel rim (::after, 300° SweepGradient, 0% RGB dispersion)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val sweep = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.parseColor("#E6FFFFFF"),
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#B8FFFFFF"),
                Color.parseColor("#1E0F172A"),
                Color.parseColor("#F5FFFFFF"),
                Color.parseColor("#1A0F172A"),
                Color.parseColor("#E6FFFFFF")
            ),
            floatArrayOf(0.0f, 0.10f, 0.22f, 0.35f, 0.52f, 0.72f, 1.0f)
        )
        rimMatrix.reset()
        rimMatrix.setRotate(300f, cx, cy)
        sweep.setLocalMatrix(rimMatrix)
        conicRimPaint.shader = sweep
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(rect, currentRadius, currentRadius, bodyPaint)
        canvas.drawRoundRect(rect, currentRadius, currentRadius, sheenPaint)
        canvas.drawRoundRect(rect, currentRadius, currentRadius, topGlowPaint)
        canvas.restore()

        val innerR = (currentRadius - outerHairlinePaint.strokeWidth).coerceAtLeast(0f)
        canvas.drawRoundRect(innerRect, innerR, innerR, conicRimPaint)
        canvas.drawRoundRect(rect, currentRadius, currentRadius, outerHairlinePaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, currentRadius)
    }

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        sheenPaint.alpha = alpha
        topGlowPaint.alpha = alpha
        conicRimPaint.alpha = alpha
        outerHairlinePaint.alpha = (alpha * 0.10f).toInt()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
