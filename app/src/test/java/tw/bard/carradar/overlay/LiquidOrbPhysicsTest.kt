package tw.bard.carradar.overlay

import org.junit.Assert.*
import org.junit.Test

class LiquidOrbPhysicsTest {

    // Pixel 10 Pro-like metrics: density 3.0, 1236 x 2745 px, safe area top 120, bottom 2600
    private val bounds = ScreenSafeBounds(
        screenW = 1236f,
        screenH = 2745f,
        safeTop = 120f,
        safeBottom = 2600f
    )
    private val density = 3.0f

    @Test
    fun `ticket 1 - idle orb snaps 0px inside left and right edges and respects safe area`() {
        val rightOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 10f)
        val r = rightOrb.idleRadiusPx
        assertEquals(OrbState.IDLE_ORB, rightOrb.currentState())
        // Right edge of orb sits at screenW (0px gap, never clipped)
        assertEquals(bounds.screenW - r, rightOrb.centerX, 0.5f)
        // Clamped below safeTop
        assertEquals(bounds.safeTop + r, rightOrb.centerY, 0.5f)

        val leftOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 5000f)
        // Left edge of orb sits at 0px
        assertEquals(r, leftOrb.centerX, 0.5f)
        // Clamped above safeBottom
        assertEquals(bounds.safeBottom - r, leftOrb.centerY, 0.5f)
    }

    @Test
    fun `ticket 1 - release carries momentum across midline and snaps smoothly with spring`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1000f)
        orb.onTouchDown(orb.centerX, orb.centerY)
        // Pull well past snap-off (48dp = 144px) to near midline (x = 580)
        orb.onTouchMove(orb.centerX + 480f, orb.centerY, 0.016f)
        assertFalse(orb.isPeelingFromEdge)

        // Fling rightward across midline -> should decide RIGHT edge
        val wasTap = orb.onTouchUp(orb.centerX, orb.centerY, flingVx = 1800f, flingVy = 0f)
        assertFalse(wasTap)
        assertEquals(OrbSide.RIGHT, orb.snappedSide)

        // Step physics until settled
        repeat(180) { orb.step(0.016f) }
        assertEquals(bounds.screenW - orb.idleRadiusPx, orb.centerX, 1.0f)
    }

    @Test
    fun `ticket 2 - direction-aware alert morph locks left or right edge at 0px and retracts symmetrically`() {
        val leftOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        leftOrb.setAlertActive(true)
        repeat(120) { leftOrb.step(0.016f) }

        assertEquals(1f, leftOrb.morphProgress, 0.02f)
        val expandedR = leftOrb.currentRadiusPx()
        assertTrue(expandedR > leftOrb.idleRadiusPx * 1.6f)
        // Left edge remains pinned at 0px -> centerX == expandedR
        assertEquals(expandedR, leftOrb.centerX, 1.0f)

        // Retract symmetrically
        leftOrb.setAlertActive(false)
        repeat(120) { leftOrb.step(0.016f) }
        assertEquals(0f, leftOrb.morphProgress, 0.02f)
        assertEquals(leftOrb.idleRadiusPx, leftOrb.centerX, 1.0f)

        // Mirror check on RIGHT edge
        val rightOrb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 1200f)
        rightOrb.setAlertActive(true)
        repeat(120) { rightOrb.step(0.016f) }
        // Right edge remains pinned at screenW -> centerX == screenW - expandedR
        assertEquals(bounds.screenW - rightOrb.currentRadiusPx(), rightOrb.centerX, 1.0f)
    }

    @Test
    fun `ticket 3 - edge peel rubber-bands until 48dp snap-off then enters teardrop with follow-through`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.LEFT, initialCenterY = 1200f)
        val startX = orb.centerX
        val startY = orb.centerY

        orb.onTouchDown(startX, startY)
        // Pull inward 30dp (90px < 144px snap-off) -> still peeling with rubber-band resistance
        orb.onTouchMove(startX + 90f, startY, 0.016f)
        assertEquals(OrbState.DRAGGING_PEELING, orb.currentState())
        assertTrue(orb.peelStretchPx > 80f)
        assertTrue(orb.centerX < startX + 90f) // resisted by rubber-band factor
        assertFalse(orb.consumeSnapOffHaptic())

        // Pull inward 55dp (165px >= 144px snap-off) -> snaps off!
        orb.onTouchMove(startX + 165f, startY, 0.016f)
        assertEquals(OrbState.DRAGGING_TEARDROP, orb.currentState())
        assertTrue(orb.consumeSnapOffHaptic())

        // Continue dragging fast -> heavy bottom mass lags behind finger in opposite direction
        orb.onTouchMove(startX + 265f, startY - 40f, 0.016f)
        assertTrue("Bottom mass should lag behind rightward drag", orb.massOffsetX < 0f)

        // Step while holding finger still -> teardrop forms and mass oscillates/settles downward (gravity drop)
        repeat(90) { orb.step(0.016f) }
        assertEquals(1f, orb.teardropFactor, 0.05f)
        assertEquals(0f, orb.massOffsetX, 1.5f)
        assertEquals(orb.gravityDropPx, orb.massOffsetY, 1.5f)
    }

    @Test
    fun `ticket 3 - dragging during alert expanded preserves expanded radius in teardrop mode`() {
        val orb = LiquidOrbPhysics(density, bounds, initialSide = OrbSide.RIGHT, initialCenterY = 1200f)
        orb.setAlertActive(true)
        repeat(120) { orb.step(0.016f) }

        val expandedR = orb.currentRadiusPx()
        orb.onTouchDown(orb.centerX, orb.centerY)
        orb.onTouchMove(orb.centerX - 200f, orb.centerY, 0.016f)
        repeat(30) { orb.step(0.016f) }

        assertEquals(OrbState.DRAGGING_TEARDROP, orb.currentState())
        assertEquals(1f, orb.morphProgress, 0.05f)
        assertEquals(expandedR, orb.currentRadiusPx(), 2.0f)
    }
}
