package tw.bard.carradar.overlay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Tunable physical constants for the Floating Liquid Orb.
 * Isolated here for rapid prototype tuning without touching rendering code.
 */
object LiquidOrbPhysicsConfig {
    // Geometry
    const val IDLE_DIAMETER_DP = 64f
    const val EXPAND_SCALE = 1.72f            // 1.6x ~ 1.8x spec (~110dp expanded)
    const val SNAP_OFF_DP = 48f               // Horizontal pull distance to break edge surface tension
    const val DRAG_SLOP_DP = 6f

    // Edge Surface Tension (Rubber-banding before Snap-off)
    const val RUBBER_BAND_FACTOR = 0.42f      // Visual travel ratio while still attached to edge

    // Edge Snap Spring (after release, carries drag momentum)
    const val EDGE_SNAP_STIFFNESS = 340f
    const val EDGE_SNAP_DAMPING = 24f
    const val MOMENTUM_PROJECTION_SEC = 0.14f

    // Alert Morph Spring (Direction-aware expansion & symmetric retraction)
    const val MORPH_STIFFNESS = 260f
    const val MORPH_DAMPING = 22f

    // Teardrop Morph & Pendulum Follow-through Spring (heavy bottom mass)
    const val TEARDROP_STIFFNESS = 320f
    const val TEARDROP_DAMPING = 24f
    const val PENDULUM_STIFFNESS = 190f
    const val PENDULUM_DAMPING = 13.5f
    const val MAX_SWAY_DP = 24f
    const val TEARDROP_GRAVITY_DROP_DP = 14f  // Downward sag of heavy bottom below finger anchor
}

enum class OrbSide {
    LEFT, RIGHT
}

enum class OrbState {
    IDLE_ORB,
    ALERT_EXPANDED,
    DRAGGING_PEELING,
    DRAGGING_TEARDROP
}

data class ScreenSafeBounds(
    val screenW: Float,
    val screenH: Float,
    val safeTop: Float,
    val safeBottom: Float
)

/**
 * Pure-Kotlin physics & state machine for the Floating Liquid Orb.
 * Single source of truth for screen-space center, morph progress, edge peel bridge,
 * momentum edge snapping, and teardrop follow-through sway.
 */
class LiquidOrbPhysics(
    val density: Float,
    var bounds: ScreenSafeBounds,
    initialSide: OrbSide = OrbSide.RIGHT,
    initialCenterY: Float = -1f
) {
    val idleRadiusPx: Float get() = (LiquidOrbPhysicsConfig.IDLE_DIAMETER_DP * density) * 0.5f
    val expandedRadiusPx: Float get() = idleRadiusPx * LiquidOrbPhysicsConfig.EXPAND_SCALE
    val snapOffPx: Float get() = LiquidOrbPhysicsConfig.SNAP_OFF_DP * density
    val dragSlopPx: Float get() = LiquidOrbPhysicsConfig.DRAG_SLOP_DP * density
    val maxSwayPx: Float get() = LiquidOrbPhysicsConfig.MAX_SWAY_DP * density
    val gravityDropPx: Float get() = LiquidOrbPhysicsConfig.TEARDROP_GRAVITY_DROP_DP * density

    var snappedSide: OrbSide = initialSide
        private set

    var isAlertActive: Boolean = false
        private set

    // 0f = Idle Orb, 1f = Alert Expanded
    var morphProgress: Float = 0f
        private set
    private var morphVel: Float = 0f

    // 0f = Orb/Circle, 1f = Full Teardrop
    var teardropFactor: Float = 0f
        private set
    private var teardropVel: Float = 0f

    // Screen-space center of the main orb (or finger anchor when in teardrop mode)
    var centerX: Float = 0f
        private set
    var centerY: Float = 0f
        private set

    var velX: Float = 0f
        private set
    var velY: Float = 0f
        private set

    // Drag & Peel state
    var isDragging: Boolean = false
        private set
    var isPeelingFromEdge: Boolean = false
        private set
    var peelStretchPx: Float = 0f
        private set
    var justSnappedOff: Boolean = false
        private set

    // Heavy bottom center-of-mass offset relative to (centerX, centerY) during teardrop drag
    var massOffsetX: Float = 0f
        private set
    var massOffsetY: Float = 0f
        private set
    private var massVelX: Float = 0f
    private var massVelY: Float = 0f

    // Touch tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var downCenterX = 0f
    private var downCenterY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var hasExceededSlop = false

    init {
        val r = idleRadiusPx
        centerX = snappedEdgeCenterX(snappedSide, r)
        val defaultY = if (initialCenterY > 0f) initialCenterY else bounds.safeTop + (bounds.safeBottom - bounds.safeTop) * 0.35f
        centerY = clampY(defaultY, r)
    }

    fun currentRadiusPx(): Float {
        val clampedMorph = morphProgress.coerceIn(0f, 1.15f)
        return idleRadiusPx * (1f + (LiquidOrbPhysicsConfig.EXPAND_SCALE - 1f) * clampedMorph)
    }

    fun currentState(): OrbState = when {
        isDragging && isPeelingFromEdge -> OrbState.DRAGGING_PEELING
        isDragging -> OrbState.DRAGGING_TEARDROP
        isAlertActive || morphProgress > 0.05f -> OrbState.ALERT_EXPANDED
        else -> OrbState.IDLE_ORB
    }

    fun setAlertActive(active: Boolean) {
        isAlertActive = active
    }

    fun consumeSnapOffHaptic(): Boolean {
        if (justSnappedOff) {
            justSnappedOff = false
            return true
        }
        return false
    }

    fun updateBounds(newBounds: ScreenSafeBounds) {
        bounds = newBounds
        if (!isDragging) {
            val r = currentRadiusPx()
            centerX = snappedEdgeCenterX(snappedSide, r)
            centerY = clampY(centerY, r)
        }
    }

    fun onTouchDown(rawX: Float, rawY: Float) {
        isDragging = true
        hasExceededSlop = false
        downRawX = rawX
        downRawY = rawY
        lastRawX = rawX
        lastRawY = rawY
        downCenterX = centerX
        downCenterY = centerY
        velX = 0f
        velY = 0f
        justSnappedOff = false

        val r = currentRadiusPx()
        val nearLeft = snappedSide == OrbSide.LEFT && abs(centerX - r) <= 8f * density
        val nearRight = snappedSide == OrbSide.RIGHT && abs(centerX - (bounds.screenW - r)) <= 8f * density
        isPeelingFromEdge = nearLeft || nearRight
        peelStretchPx = 0f
    }

    fun onTouchMove(rawX: Float, rawY: Float, dtSec: Float) {
        if (!isDragging) return
        val dxFromDown = rawX - downRawX
        val dyFromDown = rawY - downRawY
        if (!hasExceededSlop && hypot(dxFromDown, dyFromDown) >= dragSlopPx) {
            hasExceededSlop = true
        }
        if (!hasExceededSlop) return

        val safeDt = dtSec.coerceIn(0.004f, 0.05f)
        val stepDx = rawX - lastRawX
        val stepDy = rawY - lastRawY
        lastRawX = rawX
        lastRawY = rawY

        val r = currentRadiusPx()

        if (isPeelingFromEdge) {
            // Inward pull distance from the snapped edge
            val inwardPull = if (snappedSide == OrbSide.LEFT) {
                dxFromDown.coerceAtLeast(0f)
            } else {
                (-dxFromDown).coerceAtLeast(0f)
            }

            if (inwardPull >= snapOffPx) {
                // Snap-off! Surface tension breaks cleanly
                isPeelingFromEdge = false
                peelStretchPx = 0f
                justSnappedOff = true

                val targetX = clampX(downCenterX + dxFromDown, r)
                val targetY = clampY(downCenterY + dyFromDown, r)
                // Impart slight opposite recoil to heavy bottom mass for liquid snap feel
                massOffsetX = (centerX - targetX).coerceIn(-maxSwayPx, maxSwayPx)
                massOffsetY = (centerY - targetY + gravityDropPx).coerceIn(-maxSwayPx, maxSwayPx)
                centerX = targetX
                centerY = targetY
            } else {
                // Rubber-banding resistance while attached via liquid bridge
                peelStretchPx = inwardPull
                val resistedX = inwardPull * LiquidOrbPhysicsConfig.RUBBER_BAND_FACTOR
                val signedOffset = if (snappedSide == OrbSide.LEFT) resistedX else -resistedX
                centerX = clampX(snappedEdgeCenterX(snappedSide, r) + signedOffset, r)
                centerY = clampY(downCenterY + dyFromDown * 0.72f, r)
            }
            velX = stepDx / safeDt
            velY = stepDy / safeDt
        } else {
            val prevX = centerX
            val prevY = centerY
            centerX = clampX(downCenterX + dxFromDown, r)
            centerY = clampY(downCenterY + dyFromDown, r)

            val moveDx = centerX - prevX
            val moveDy = centerY - prevY

            // Finger moved -> heavy bottom center of mass lags behind (opposite direction)
            massOffsetX = (massOffsetX - moveDx).coerceIn(-maxSwayPx, maxSwayPx)
            massOffsetY = (massOffsetY - moveDy).coerceIn(-maxSwayPx, maxSwayPx)

            // Track smoothed velocity for release momentum
            val instVx = moveDx / safeDt
            val instVy = moveDy / safeDt
            velX = velX * 0.55f + instVx * 0.45f
            velY = velY * 0.55f + instVy * 0.45f
        }
    }

    /**
     * @return true if this touch gesture was a tap (did not exceed drag slop), false if dragged.
     */
    fun onTouchUp(rawX: Float, rawY: Float, flingVx: Float = velX, flingVy: Float = velY): Boolean {
        val wasTap = !hasExceededSlop
        isDragging = false
        isPeelingFromEdge = false
        peelStretchPx = 0f

        if (wasTap) {
            velX = 0f
            velY = 0f
            return true
        }

        // Carry momentum into edge-snap decision (projected centerX vs screen horizontal midline)
        velX = flingVx.coerceIn(-4500f, 4500f)
        velY = flingVy.coerceIn(-4500f, 4500f)
        val projectedX = centerX + velX * LiquidOrbPhysicsConfig.MOMENTUM_PROJECTION_SEC
        snappedSide = if (projectedX < bounds.screenW * 0.5f) OrbSide.LEFT else OrbSide.RIGHT
        return false
    }

    /**
     * Advances all spring simulations by dtSec.
     * @return true if any animation/physics is still active.
     */
    fun step(dtSec: Float): Boolean {
        val dt = dtSec.coerceIn(0.001f, 0.033f)

        // 1. Alert Morph Spring (0 <-> 1)
        val morphTarget = if (isAlertActive) 1f else 0f
        val morphAcc = (morphTarget - morphProgress) * LiquidOrbPhysicsConfig.MORPH_STIFFNESS -
            morphVel * LiquidOrbPhysicsConfig.MORPH_DAMPING
        morphVel += morphAcc * dt
        morphProgress += morphVel * dt
        if (abs(morphTarget - morphProgress) < 0.002f && abs(morphVel) < 0.01f) {
            morphProgress = morphTarget
            morphVel = 0f
        }

        // 2. Teardrop Shell Factor Spring (0 <-> 1)
        val tearTarget = if (isDragging && !isPeelingFromEdge && hasExceededSlop) 1f else 0f
        val tearAcc = (tearTarget - teardropFactor) * LiquidOrbPhysicsConfig.TEARDROP_STIFFNESS -
            teardropVel * LiquidOrbPhysicsConfig.TEARDROP_DAMPING
        teardropVel += tearAcc * dt
        teardropFactor = (teardropFactor + teardropVel * dt).coerceIn(0f, 1.08f)
        if (abs(tearTarget - teardropFactor) < 0.002f && abs(teardropVel) < 0.01f) {
            teardropFactor = tearTarget
            teardropVel = 0f
        }

        // 3. Heavy Bottom Mass Pendulum Spring (Follow-through & gravity drop)
        val targetMassX = 0f
        val targetMassY = if (tearTarget > 0.5f) gravityDropPx else 0f
        val mAccX = (targetMassX - massOffsetX) * LiquidOrbPhysicsConfig.PENDULUM_STIFFNESS -
            massVelX * LiquidOrbPhysicsConfig.PENDULUM_DAMPING
        val mAccY = (targetMassY - massOffsetY) * LiquidOrbPhysicsConfig.PENDULUM_STIFFNESS -
            massVelY * LiquidOrbPhysicsConfig.PENDULUM_DAMPING
        massVelX += mAccX * dt
        massVelY += mAccY * dt
        massOffsetX = (massOffsetX + massVelX * dt).coerceIn(-maxSwayPx * 1.2f, maxSwayPx * 1.2f)
        massOffsetY = (massOffsetY + massVelY * dt).coerceIn(-maxSwayPx * 1.2f, maxSwayPx * 1.2f)
        if (abs(targetMassX - massOffsetX) < 0.15f && abs(massVelX) < 1f) {
            massOffsetX = targetMassX
            massVelX = 0f
        }
        if (abs(targetMassY - massOffsetY) < 0.15f && abs(massVelY) < 1f) {
            massOffsetY = targetMassY
            massVelY = 0f
        }

        // 4. Edge-snap Spring & Origin-aware Anchor Lock (when not dragging)
        val r = currentRadiusPx()
        var posSettled = true
        if (!isDragging) {
            val targetX = snappedEdgeCenterX(snappedSide, r)
            // If already settled on edge during pure alert morph (velX == 0), lock edge strictly at 0px!
            if (abs(velX) < 1f && abs(centerX - targetX) <= (expandedRadiusPx - idleRadiusPx) + 2f && abs(morphVel) > 0.001f) {
                centerX = targetX
                velX = 0f
            } else {
                val accX = (targetX - centerX) * LiquidOrbPhysicsConfig.EDGE_SNAP_STIFFNESS -
                    velX * LiquidOrbPhysicsConfig.EDGE_SNAP_DAMPING
                velX += accX * dt
                centerX += velX * dt
                // Hard clamp to never leave screen
                val clampedX = clampX(centerX, r)
                if (clampedX != centerX) {
                    centerX = clampedX
                    if (sign(velX) == sign(centerX - targetX)) velX *= -0.25f
                }
            }

            // Y momentum damping + safe area clamping
            if (abs(velY) > 1f) {
                velY *= (1f - 9f * dt).coerceAtLeast(0f)
                centerY += velY * dt
            } else {
                velY = 0f
            }
            val clampedY = clampY(centerY, r)
            if (clampedY != centerY) {
                centerY = clampedY
                velY = 0f
            }

            if (abs(targetX - centerX) < 0.4f && abs(velX) < 2f && abs(velY) < 2f) {
                centerX = targetX
                velX = 0f
                velY = 0f
            } else {
                posSettled = false
            }
        } else {
            posSettled = false
        }

        val morphSettled = (morphProgress == morphTarget && morphVel == 0f)
        val tearSettled = (teardropFactor == tearTarget && teardropVel == 0f)
        val massSettled = (massOffsetX == targetMassX && massOffsetY == targetMassY && massVelX == 0f && massVelY == 0f)

        return !(posSettled && morphSettled && tearSettled && massSettled)
    }

    fun snappedEdgeCenterX(side: OrbSide, radiusPx: Float): Float {
        return when (side) {
            OrbSide.LEFT -> radiusPx
            OrbSide.RIGHT -> (bounds.screenW - radiusPx).coerceAtLeast(radiusPx)
        }
    }

    private fun clampX(x: Float, r: Float): Float {
        val minX = r
        val maxX = (bounds.screenW - r).coerceAtLeast(minX)
        return x.coerceIn(minX, maxX)
    }

    private fun clampY(y: Float, r: Float): Float {
        val minY = bounds.safeTop + r
        val maxY = (bounds.safeBottom - r).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }
}
