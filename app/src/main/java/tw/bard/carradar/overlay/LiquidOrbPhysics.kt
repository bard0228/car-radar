package tw.bard.carradar.overlay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Tunable physical constants for the Floating Liquid Water-Ball.
 */
object LiquidOrbPhysicsConfig {
    // Geometry
    const val IDLE_DIAMETER_DP = 64f
    const val EXPAND_SCALE = 1.75f            // ~112dp expanded circle
    const val SNAP_OFF_DP = 48f               // Horizontal pull distance to break edge surface tension
    const val RUBBER_BAND_FACTOR = 0.42f      // Visual travel ratio while still attached to edge
    const val DRAG_SLOP_DP = 4f

    // Bottom X Dismiss Zone
    const val DISMISS_TARGET_OFFSET_BOTTOM_DP = 56f
    const val DISMISS_HIT_RADIUS_DP = 72f

    // Edge Snap Spring (after release, carries drag momentum)
    const val EDGE_SNAP_STIFFNESS = 380f
    const val EDGE_SNAP_DAMPING = 28f
    const val MOMENTUM_PROJECTION_SEC = 0.13f

    // Alert Morph Spring (Direction-aware expansion & symmetric retraction)
    const val MORPH_STIFFNESS = 280f
    const val MORPH_DAMPING = 24f

    // Subtle Jelly Water-Ball Wobble Spring
    const val JELLY_STIFFNESS = 260f
    const val JELLY_DAMPING = 18f
    const val MAX_WOBBLE_RATIO = 0.075f       // Max ±7.5% subtle squash & stretch
    const val VELOCITY_TO_WOBBLE = 0.000045f
}

enum class OrbSide {
    LEFT, RIGHT
}

enum class OrbState {
    IDLE_ORB,
    ALERT_EXPANDED,
    DRAGGING_PEELING,
    DRAGGING_WATERBALL
}

data class ScreenSafeBounds(
    val screenW: Float,
    val screenH: Float,
    val safeTop: Float,
    val safeBottom: Float
)

data class TouchUpResult(
    val wasTap: Boolean,
    val dismissed: Boolean
)

/**
 * Pure-Kotlin physics & state machine for the Floating Liquid Water-Ball:
 * - Edge surface tension peel (48dp rubber-band + water bridge + snap-off haptic)
 * - Instant 1:1 finger tracking + subtle jelly wobble once detached
 * - Bottom X dismiss target hit detection
 * - Momentum edge snapping & 0px origin-locked alert expansion/retraction
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
    val dismissHitRadiusPx: Float get() = LiquidOrbPhysicsConfig.DISMISS_HIT_RADIUS_DP * density

    var snappedSide: OrbSide = initialSide
        private set

    var isAlertActive: Boolean = false
        private set

    // 0f = Idle Orb, 1f = Alert Expanded
    var morphProgress: Float = 0f
        private set
    private var morphVel: Float = 0f

    // Screen-space center of the water-ball
    var centerX: Float = 0f
        private set
    var centerY: Float = 0f
        private set

    var velX: Float = 0f
        private set
    var velY: Float = 0f
        private set

    var isDragging: Boolean = false
        private set
    var isPeelingFromEdge: Boolean = false
        private set
    var peelStretchPx: Float = 0f
        private set
    var justSnappedOff: Boolean = false
        private set
    var isHoveringDismiss: Boolean = false
        private set
    var justEnteredDismiss: Boolean = false
        private set

    // Subtle jelly wobble vector (normalized [-MAX_WOBBLE, +MAX_WOBBLE])
    var wobbleX: Float = 0f
        private set
    var wobbleY: Float = 0f
        private set
    private var wobbleVelX: Float = 0f
    private var wobbleVelY: Float = 0f

    // Touch tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var downCenterX = 0f
    private var downCenterY = 0f
    private var hasExceededSlop = false

    init {
        val r = idleRadiusPx
        centerX = snappedEdgeCenterX(snappedSide, r)
        val defaultY = if (initialCenterY > 0f) initialCenterY else bounds.safeTop + (bounds.safeBottom - bounds.safeTop) * 0.35f
        centerY = clampY(defaultY, r)
    }

    fun currentRadiusPx(): Float {
        val clampedMorph = morphProgress.coerceIn(0f, 1.12f)
        return idleRadiusPx * (1f + (LiquidOrbPhysicsConfig.EXPAND_SCALE - 1f) * clampedMorph)
    }

    fun currentState(): OrbState = when {
        isDragging && isPeelingFromEdge -> OrbState.DRAGGING_PEELING
        isDragging -> OrbState.DRAGGING_WATERBALL
        isAlertActive || morphProgress > 0.05f -> OrbState.ALERT_EXPANDED
        else -> OrbState.IDLE_ORB
    }

    fun dismissCenterX(): Float = bounds.screenW * 0.5f

    fun dismissCenterY(): Float =
        bounds.safeBottom - LiquidOrbPhysicsConfig.DISMISS_TARGET_OFFSET_BOTTOM_DP * density

    fun shouldShowDismissTarget(): Boolean = isDragging && !isPeelingFromEdge && hasExceededSlop

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

    fun consumeDismissHoverHaptic(): Boolean {
        if (justEnteredDismiss) {
            justEnteredDismiss = false
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
        downCenterX = centerX
        downCenterY = centerY
        velX = 0f
        velY = 0f
        justSnappedOff = false
        isHoveringDismiss = false
        justEnteredDismiss = false

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
        val r = currentRadiusPx()

        if (isPeelingFromEdge) {
            val inwardPull = if (snappedSide == OrbSide.LEFT) {
                dxFromDown.coerceAtLeast(0f)
            } else {
                (-dxFromDown).coerceAtLeast(0f)
            }

            if (inwardPull >= snapOffPx) {
                // Snap-off! Surface tension breaks cleanly with subtle recoil wobble
                isPeelingFromEdge = false
                peelStretchPx = 0f
                justSnappedOff = true

                val dir = if (snappedSide == OrbSide.LEFT) 1f else -1f
                wobbleVelX += dir * 0.9f
                centerX = clampX(downCenterX + dxFromDown, r)
                centerY = clampY(downCenterY + dyFromDown, r)
            } else {
                // Rubber-banding resistance while attached via liquid bridge
                peelStretchPx = inwardPull
                val resistedX = inwardPull * LiquidOrbPhysicsConfig.RUBBER_BAND_FACTOR
                val signedOffset = if (snappedSide == OrbSide.LEFT) resistedX else -resistedX
                centerX = clampX(snappedEdgeCenterX(snappedSide, r) + signedOffset, r)
                centerY = clampY(downCenterY + dyFromDown * 0.72f, r)
                // Gentle horizontal stretch along peel direction
                val stretchRatio = (inwardPull / snapOffPx) * LiquidOrbPhysicsConfig.MAX_WOBBLE_RATIO
                wobbleX = if (snappedSide == OrbSide.LEFT) stretchRatio else -stretchRatio
                wobbleY = 0f
            }
            isHoveringDismiss = false
        } else {
            val prevX = centerX
            val prevY = centerY
            // Direct 1:1 instant finger tracking inside safe bounds
            centerX = clampX(downCenterX + dxFromDown, r)
            centerY = clampY(downCenterY + dyFromDown, r)

            val moveDx = centerX - prevX
            val moveDy = centerY - prevY
            val instVx = moveDx / safeDt
            val instVy = moveDy / safeDt
            velX = velX * 0.5f + instVx * 0.5f
            velY = velY * 0.5f + instVy * 0.5f

            val maxW = LiquidOrbPhysicsConfig.MAX_WOBBLE_RATIO
            val targetWx = (velX * LiquidOrbPhysicsConfig.VELOCITY_TO_WOBBLE).coerceIn(-maxW, maxW)
            val targetWy = (velY * LiquidOrbPhysicsConfig.VELOCITY_TO_WOBBLE).coerceIn(-maxW, maxW)
            wobbleX = wobbleX * 0.72f + targetWx * 0.28f
            wobbleY = wobbleY * 0.72f + targetWy * 0.28f

            // Check bottom X dismiss zone hit
            val distToDismiss = hypot(centerX - dismissCenterX(), centerY - dismissCenterY())
            val nowHovering = distToDismiss <= dismissHitRadiusPx
            if (nowHovering && !isHoveringDismiss) {
                justEnteredDismiss = true
            }
            isHoveringDismiss = nowHovering
        }
    }

    fun onTouchUp(flingVx: Float = velX, flingVy: Float = velY): TouchUpResult {
        val wasTap = !hasExceededSlop
        val shouldDismiss = !wasTap && !isPeelingFromEdge && isHoveringDismiss
        isDragging = false
        isPeelingFromEdge = false
        peelStretchPx = 0f
        isHoveringDismiss = false

        if (wasTap) {
            velX = 0f
            velY = 0f
            return TouchUpResult(wasTap = true, dismissed = false)
        }
        if (shouldDismiss) {
            velX = 0f
            velY = 0f
            return TouchUpResult(wasTap = false, dismissed = true)
        }

        velX = flingVx.coerceIn(-4500f, 4500f)
        velY = flingVy.coerceIn(-4500f, 4500f)
        val projectedX = centerX + velX * LiquidOrbPhysicsConfig.MOMENTUM_PROJECTION_SEC
        snappedSide = if (projectedX < bounds.screenW * 0.5f) OrbSide.LEFT else OrbSide.RIGHT
        return TouchUpResult(wasTap = false, dismissed = false)
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

        // 2. Subtle Jelly Wobble Spring (relaxes smoothly toward 0 when not peeling)
        if (!isPeelingFromEdge) {
            val jAccX = -wobbleX * LiquidOrbPhysicsConfig.JELLY_STIFFNESS - wobbleVelX * LiquidOrbPhysicsConfig.JELLY_DAMPING
            val jAccY = -wobbleY * LiquidOrbPhysicsConfig.JELLY_STIFFNESS - wobbleVelY * LiquidOrbPhysicsConfig.JELLY_DAMPING
            wobbleVelX += jAccX * dt
            wobbleVelY += jAccY * dt
            val maxW = LiquidOrbPhysicsConfig.MAX_WOBBLE_RATIO
            wobbleX = (wobbleX + wobbleVelX * dt).coerceIn(-maxW, maxW)
            wobbleY = (wobbleY + wobbleVelY * dt).coerceIn(-maxW, maxW)
            if (abs(wobbleX) < 0.001f && abs(wobbleVelX) < 0.005f) {
                wobbleX = 0f
                wobbleVelX = 0f
            }
            if (abs(wobbleY) < 0.001f && abs(wobbleVelY) < 0.005f) {
                wobbleY = 0f
                wobbleVelY = 0f
            }
        }

        // 3. Edge-snap Spring & Origin-aware Anchor Lock (when not dragging)
        val r = currentRadiusPx()
        var posSettled = true
        if (!isDragging) {
            val targetX = snappedEdgeCenterX(snappedSide, r)
            // Always lock edge strictly at 0px during pure alert expand/retract when not flinging
            if (abs(velX) < 1f && abs(centerX - targetX) <= (expandedRadiusPx - idleRadiusPx) + 6f) {
                centerX = targetX
                velX = 0f
            } else {
                val accX = (targetX - centerX) * LiquidOrbPhysicsConfig.EDGE_SNAP_STIFFNESS -
                    velX * LiquidOrbPhysicsConfig.EDGE_SNAP_DAMPING
                velX += accX * dt
                centerX += velX * dt
                val clampedX = clampX(centerX, r)
                if (clampedX != centerX) {
                    centerX = clampedX
                    wobbleVelX -= sign(velX) * 0.4f
                    if (sign(velX) == sign(centerX - targetX)) velX *= -0.18f
                }
            }

            if (abs(velY) > 1f) {
                velY *= (1f - 10f * dt).coerceAtLeast(0f)
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
        val wobbleSettled = (wobbleX == 0f && wobbleY == 0f && wobbleVelX == 0f && wobbleVelY == 0f)

        return !(posSettled && morphSettled && wobbleSettled)
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
