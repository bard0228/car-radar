package tw.bard.carradar.core

import org.junit.Assert.*
import org.junit.Test

class RadarCoreTest {

    @Test
    fun `approach alerts only once even when speeding near camera, then alerts pass`() {
        val alerter = CameraAlerter()
        // Camera at (25.0045, 121.5000), heading North (0 deg), limit 80
        val cam = CameraPoint(
            id = "cam1",
            lat = 25.0045,
            lon = 121.5000,
            limitKmh = 80,
            directionText = "北向",
            address = "測試路段",
            city = "台北市",
            kind = CameraKind.SPEED,
            headingDeg = 0f,
            bidirectional = false
        )

        // 1. ~278m south, moving north at 75 km/h -> midSpeedDistM = 300m -> triggers once ["80"]
        val (e1, hud1) = alerter.update(listOf(cam), 25.0020, 121.5000, 75f, 0f)
        assertTrue(e1 is RadarAlertEvent.Approach)
        assertEquals(listOf("80"), e1?.phraseKeys)
        assertNotNull(hud1)

        // 2. ~111m south, speeding at 110 km/h -> MUST NOT re-alert
        val (e2, _) = alerter.update(listOf(cam), 25.0035, 121.5000, 110f, 0f)
        assertNull(e2)

        // 3. ~22m south (within passWithinM=65m) -> updates minDist
        val (e3, _) = alerter.update(listOf(cam), 25.0043, 121.5000, 90f, 0f)
        assertNull(e3)

        // 4. ~10m north (just passed camera, camera is 180 deg behind, d=10m < minDist) -> updates minDist, MUST NOT be swallowed as turn-away
        val (e4a, _) = alerter.update(listOf(cam), 25.0046, 121.5000, 80f, 0f)
        assertNull(e4a)

        // 5. ~25m north (camera is 180 deg behind and distance increased >= 12m past minDist) -> triggers Passed ["pass"]!
        val (e4, _) = alerter.update(listOf(cam), 25.00475, 121.5000, 80f, 0f)
        assertTrue(e4 is RadarAlertEvent.Passed)
        assertEquals(listOf("pass"), e4?.phraseKeys)
    }

    @Test
    fun `plan A phrase keys follow strict minimal vocabulary`() {
        val speedTech = CameraPoint("1", 25.0, 121.5, 50, "", "", "", CameraKind.TECH)
        assertEquals(listOf("50"), speedTech.alertPhraseKeys())

        val redLight = CameraPoint("2", 25.0, 121.5, 0, "", "", "", CameraKind.RED_LIGHT)
        assertEquals(listOf("red_light"), redLight.alertPhraseKeys())

        val pureTech = CameraPoint("3", 25.0, 121.5, 0, "", "", "", CameraKind.TECH)
        assertEquals(listOf("tech"), pureTech.alertPhraseKeys())
    }

    @Test
    fun `low speed uses 200m and mid speed uses 300m and syncs HUD`() {
        val cam = CameraPoint("c1", 25.0023, 121.5, 60, "", "", "", CameraKind.SPEED)
        val alerter = CameraAlerter()

        // ~255m away: 50 km/h (low -> 200m) should NOT trigger AND HUD stays null (no premature expansion)
        val (eLow, hudLow) = alerter.update(listOf(cam), 25.0, 121.5, 50f, 0f)
        assertNull(eLow)
        assertNull(hudLow)

        // ~255m away: 70 km/h (mid -> 300m) SHOULD trigger AND expand HUD synchronously
        val (eMid, hudMid) = alerter.update(listOf(cam), 25.0, 121.5, 70f, 0f)
        assertTrue(eMid is RadarAlertEvent.Approach)
        assertNotNull(hudMid)
    }

    @Test
    fun `parallel road or right-side camera beyond 32m lateral offset is ignored`() {
        val alerter = CameraAlerter()
        // Camera is ~220m ahead-east, ~70m laterally east of our northbound lane (parallel road)
        val parallelCam = CameraPoint("p1", 25.0018, 121.5007, 50, "", "", "", CameraKind.TECH)
        val (e, hud) = alerter.update(listOf(parallelCam), 25.0, 121.5000, 50f, 0f)
        assertNull(e)
        assertNull(hud)
    }

    @Test
    fun `null heading never blindly alerts 360 degrees`() {
        val alerter = CameraAlerter()
        val cam = CameraPoint("c_null", 25.0010, 121.5000, 50, "", "", "", CameraKind.SPEED)
        val (e, hud) = alerter.update(listOf(cam), 25.0, 121.5000, 40f, null)
        assertNull(e)
        assertNull(hud)
    }

    @Test
    fun `turning away before reaching camera silently retracts HUD without pass voice`() {
        val alerter = CameraAlerter()
        val cam = CameraPoint("c_turn", 25.0018, 121.5000, 50, "", "", "", CameraKind.SPEED)

        // 1. ~166m away, heading North (0 deg) at 50 km/h (<200m) -> alerts ["50"]
        val (e1, hud1) = alerter.update(listOf(cam), 25.0003, 121.5000, 50f, 0f)
        assertTrue(e1 is RadarAlertEvent.Approach)
        assertNotNull(hud1)

        // 2. Driver turns right (heading East 90 deg) at ~120m away (never entered 65m) -> silently retracts, NO Passed event!
        val (e2, hud2) = alerter.update(listOf(cam), 25.0007, 121.5003, 40f, 90f)
        assertNull(e2)
        assertNull(hud2)
    }

    @Test
    fun `opposite direction single-way camera is ignored`() {
        val cam = CameraPoint("c2", 25.0020, 121.5, 60, "南向", "", "", CameraKind.SPEED, headingDeg = 180f, bidirectional = false)
        val alerter = CameraAlerter()
        val (e, _) = alerter.update(listOf(cam), 25.0, 121.5, 60f, 0f)
        assertNull(e)
    }

    @Test
    fun `interval zone alerts on entry and exit only`() {
        val zone = IntervalZone("z1", "測試區間", 60, 1000, 25.0000, 121.5, 25.0090, 121.5)
        val tracker = IntervalTracker()

        val eEnter = tracker.update(listOf(zone), 25.0002, 121.5, 55f)
        assertTrue(eEnter is IntervalAlertEvent.Entered)
        assertEquals(listOf("interval", "60"), eEnter?.phraseKeys)

        val eMid = tracker.update(listOf(zone), 25.0050, 121.5, 95f)
        assertNull(eMid)

        assertNull(tracker.update(listOf(zone), 25.0089, 121.5, 60f))
        val eExit = tracker.update(listOf(zone), 25.0095, 121.5, 60f)
        assertTrue(eExit is IntervalAlertEvent.Exited)
        assertEquals(listOf("pass"), eExit?.phraseKeys)
    }

    @Test
    fun `mileage calculator filters drift and formats taipei date time`() {
        val calc = MileageCalculator()
        val startMs = 1763798400000L
        calc.startSession(startMs)

        calc.onLocation(25.0000, 121.5000, 0f, startMs)
        calc.onLocation(25.0002, 121.5000, 1.0f, startMs + 5_000)
        assertEquals(0.0, calc.currentKm(), 0.001)

        val km = calc.onLocation(25.0100, 121.5000, 40f, startMs + 105_000)
        assertTrue(km > 1.0 && km < 1.2)

        val record = calc.buildRecord(startMs + 105_000)
        assertNotNull(record)
        assertTrue(record!!.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue(record.startTime.matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(record.endTime.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `expressway mode suppresses surface tech and 50kmh cameras but still alerts expressway cameras`() {
        val alerter = CameraAlerter()
        val surfaceRedLight = CameraPoint(
            id = "s_red", lat = 25.0015, lon = 121.5000, limitKmh = 0,
            directionText = "雙向", address = "中山路路口", city = "台北市",
            kind = CameraKind.RED_LIGHT, roadLevel = RoadLevel.SURFACE
        )
        val surface50 = CameraPoint(
            id = "s_50", lat = 25.0016, lon = 121.5000, limitKmh = 50,
            directionText = "雙向", address = "平面道路50", city = "台北市",
            kind = CameraKind.SPEED, roadLevel = RoadLevel.SURFACE
        )
        val express80 = CameraPoint(
            id = "e_80", lat = 25.0030, lon = 121.5000, limitKmh = 80,
            directionText = "北向", address = "台74線快速道路", city = "台中市",
            kind = CameraKind.SPEED, headingDeg = 0f, bidirectional = false,
            roadLevel = RoadLevel.EXPRESSWAY
        )

        val t0 = 1_000_000L
        // Sustain >= 75 km/h for 6 seconds -> enters Expressway Mode
        alerter.update(emptyList(), 24.9900, 121.5000, 88f, 0f, t0)
        alerter.update(emptyList(), 24.9950, 121.5000, 90f, 0f, t0 + 6_000L)
        assertTrue("Should enter expressway mode", alerter.isExpresswayMode)

        // Surface red-light & 50 km/h underneath expressway MUST be suppressed
        val (eSurf, hudSurf) = alerter.update(listOf(surfaceRedLight, surface50), 25.0005, 121.5000, 90f, 0f, t0 + 8_000L)
        assertNull(eSurf)
        assertNull(hudSurf)

        // Expressway 80 km/h camera MUST still alert
        val (eExp, hudExp) = alerter.update(listOf(express80), 25.0005, 121.5000, 90f, 0f, t0 + 9_000L)
        assertTrue(eExp is RadarAlertEvent.Approach)
        assertEquals(listOf("80"), eExp?.phraseKeys)
        assertNotNull(hudExp)

        // Slow down < 55 km/h for 7 seconds (exited ramp onto surface street) -> exits Expressway Mode
        alerter.update(emptyList(), 25.0005, 121.5000, 40f, 0f, t0 + 20_000L)
        alerter.update(emptyList(), 25.0005, 121.5000, 35f, 0f, t0 + 27_000L)
        assertFalse("Should return to surface mode", alerter.isExpresswayMode)
    }
}
