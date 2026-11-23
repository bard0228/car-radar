package tw.bard.carradar.overlay

import org.junit.Assert.*
import org.junit.Test

class LiquidOrbPhysicsTest {

    private val bounds = ScreenSafeBounds(
        screenW = 1236f,
        screenH = 2745f,
        safeTop = 120f,
        safeBottom = 2600f
    )
    private val density = 3.0f

    @Test
    fun `idle orb snaps 0px inside left and right edges and respects safe area`() {
        val rightOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 10f)
        val r = rightOrb.idleRadiusPx
        assertEquals(OrbState.IDLE_ORB, rightOrb.currentState())
        assertEquals(bounds.screenW - r, rightOrb.centerX, 0.5f)
        assertEquals(bounds.safeTop + r, rightOrb.centerY, 0.5f)

        val leftOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 5000f)
        assertEquals(r, leftOrb.centerX, 0.5f)
        assertEquals(bounds.safeBottom - r, leftOrb.centerY, 0.5f)
    }

    @Test
    fun `dragging tracks finger 1 to 1 immediately and produces subtle capped jelly wobble`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        val startX = orb.centerX
        val startY = orb.centerY

        orb.onTouchDown(startX, startY)
        orb.onTouchMove(startX + 180f, startY + 60f, 0.016f)

        assertEquals(OrbState.DRAGGING_WATERBALL, orb.currentState())
        // 1:1 instant tracking directly under finger
        assertEquals(startX + 180f, orb.centerX, 0.5f)
        assertEquals(startY + 60f, orb.centerY, 0.5f)
        // Subtle wobble capped within MAX_WOBBLE_RATIO
        assertTrue(orb.wobbleX > 0f && orb.wobbleX <= LiquidOrbPhysicsConfig.MAX_WOBBLE_RATIO)

        // Settles cleanly back to 0 wobble
        repeat(90) { orb.step(0.016f) }
        assertEquals(0f, orb.wobbleX, 0.005f)
        assertEquals(0f, orb.wobbleY, 0.005f)
    }

    @Test
    fun `release carries momentum across midline and snaps smoothly with spring`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1000f)
        orb.onTouchDown(orb.centerX, orb.centerY)
        orb.onTouchMove(orb.centerX + 480f, orb.centerY, 0.016f)

        val wasTap = orb.onTouchUp(flingVx = 1800f, flingVy = 0f)
        assertFalse(wasTap)
        assertEquals(OrbSide.RIGHT, orb.snappedSide)

        repeat(180) { orb.step(0.016f) }
        assertEquals(bounds.screenW - orb.idleRadiusPx, orb.centerX, 1.0f)
    }

    @Test
    fun `direction-aware alert morph locks left or right edge at 0px and retracts symmetrically`() {
        val leftOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        leftOrb.setAlertActive(true)
        repeat(120) { leftOrb.step(0.016f) }

        assertEquals(1f, leftOrb.morphProgress, 0.02f)
        assertEquals(leftOrb.currentRadiusPx(), leftOrb.centerX, 1.0f)

        leftOrb.setAlertActive(false)
        repeat(120) { leftOrb.step(0.016f) }
        assertEquals(0f, leftOrb.morphProgress, 0.02f)
        assertEquals(leftOrb.idleRadiusPx, leftOrb.centerX, 1.0f)

        val rightOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 1200f)
        rightOrb.setAlertActive(true)
        repeat(120) { rightOrb.step(0.016f) }
        assertEquals(bounds.screenW - rightOrb.currentRadiusPx(), rightOrb.centerX, 1.0f)
    }
}
