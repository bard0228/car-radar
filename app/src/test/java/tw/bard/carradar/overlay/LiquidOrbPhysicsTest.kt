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
    fun `edge peel rubber-bands up to 48dp snap-off with haptic then tracks 1 to 1`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        val startX = orb.centerX
        val startY = orb.centerY

        orb.onTouchDown(startX, startY)
        // 30dp inward pull (90px < 144px snap-off) -> still peeling
        orb.onTouchMove(startX + 90f, startY, 0.016f)
        assertEquals(OrbState.DRAGGING_PEELING, orb.currentState())
        assertTrue(orb.peelStretchPx > 80f)
        assertTrue(orb.centerX < startX + 90f)
        assertFalse(orb.consumeSnapOffHaptic())
        assertFalse(orb.shouldShowDismissTarget())

        // 55dp inward pull (165px >= 144px snap-off) -> snaps off with haptic & tracks 1:1!
        orb.onTouchMove(startX + 165f, startY + 30f, 0.016f)
        assertEquals(OrbState.DRAGGING_WATERBALL, orb.currentState())
        assertTrue(orb.consumeSnapOffHaptic())
        assertTrue(orb.shouldShowDismissTarget())
        assertEquals(startX + 165f, orb.centerX, 0.5f)
        assertEquals(startY + 30f, orb.centerY, 0.5f)
    }

    @Test
    fun `dropping water-ball inside bottom X dismiss zone returns dismissed true`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        val startX = orb.centerX
        val startY = orb.centerY

        orb.onTouchDown(startX, startY)
        // Snap off first
        orb.onTouchMove(startX + 200f, startY, 0.016f)
        assertFalse(orb.isHoveringDismiss)

        // Drag directly onto bottom X dismiss center
        val dx = orb.dismissCenterX() - startX
        val dy = orb.dismissCenterY() - startY
        orb.onTouchMove(startX + dx, startY + dy, 0.016f)
        assertTrue(orb.isHoveringDismiss)
        assertTrue(orb.consumeDismissHoverHaptic())

        val res = orb.onTouchUp()
        assertFalse(res.wasTap)
        assertTrue(res.dismissed)
    }

    @Test
    fun `release carries momentum across midline and snaps smoothly with spring`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1000f)
        orb.onTouchDown(orb.centerX, orb.centerY)
        orb.onTouchMove(orb.centerX + 480f, orb.centerY, 0.016f)

        val res = orb.onTouchUp(flingVx = 1800f, flingVy = 0f)
        assertFalse(res.wasTap)
        assertFalse(res.dismissed)
        assertEquals(OrbSide.RIGHT, orb.snappedSide)

        repeat(180) { orb.step(0.016f) }
        assertEquals(bounds.screenW - orb.idleRadiusPx, orb.centerX, 1.0f)
    }

    @Test
    fun `direction-aware alert morph locks left or right edge strictly at 0px without drifting`() {
        val leftOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        leftOrb.setAlertActive(true)
        repeat(120) {
            leftOrb.step(0.016f)
            assertEquals(leftOrb.currentRadiusPx(), leftOrb.centerX, 0.5f)
        }
        assertEquals(1f, leftOrb.morphProgress, 0.02f)

        leftOrb.setAlertActive(false)
        repeat(120) {
            leftOrb.step(0.016f)
            assertEquals(leftOrb.currentRadiusPx(), leftOrb.centerX, 0.5f)
        }
        assertEquals(0f, leftOrb.morphProgress, 0.02f)

        val rightOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 1200f)
        rightOrb.setAlertActive(true)
        repeat(120) {
            rightOrb.step(0.016f)
            assertEquals(bounds.screenW - rightOrb.currentRadiusPx(), rightOrb.centerX, 0.5f)
        }
    }
}
