package tw.bard.carradar.core

import kotlin.math.sin

sealed class RadarAlertEvent {
    abstract val camera: CameraPoint
    abstract val distanceM: Double
    abstract val phraseKeys: List<String>

    data class Approach(
        override val camera: CameraPoint,
        override val distanceM: Double,
        override val phraseKeys: List<String>
    ) : RadarAlertEvent()

    data class Passed(
        override val camera: CameraPoint,
        override val distanceM: Double,
        override val phraseKeys: List<String> = listOf("pass")
    ) : RadarAlertEvent()
}

data class ActiveCameraHud(
    val camera: CameraPoint,
    val distanceM: Int
)

class CameraAlerter(
    private val aheadToleranceDeg: Float = 28f,
    private val maxLateralOffsetM: Double = 32.0,
    private val directionToleranceDeg: Float = 60f,
    private val turnAwayDeg: Float = 60f,
    private val minSpeedKmh: Float = 10f,
    private val passWithinM: Double = 65.0,
    private val passMarginM: Double = 30.0,
    private val turnAwayMarginM: Double = 35.0,
    private val resetM: Double = 1000.0
) {
    // stage: 0/absent = none, 1 = approached (already alerted once), 2 = passed/dismissed
    private val stage = HashMap<String, Int>()
    private val minDist = HashMap<String, Double>()

    var isMuted: (CameraPoint) -> Boolean = { false }
    var distConfig: AlertDistancesConfig = AlertDistancesConfig()

    fun reset() {
        stage.clear()
        minDist.clear()
    }

    fun update(
        cameras: List<CameraPoint>,
        lat: Double,
        lon: Double,
        speedKmh: Float,
        headingDeg: Float?
    ): Pair<RadarAlertEvent?, ActiveCameraHud?> {
        // Never blindly alert 360° if we haven't acquired any valid heading yet
        if (headingDeg == null) {
            return null to null
        }

        val nearby = ArrayList<Pair<CameraPoint, Double>>()
        for (cam in cameras) {
            if (isMuted(cam)) continue
            val d = GeoMath.haversineMeters(lat, lon, cam.lat, cam.lon)
            if (d <= resetM) {
                nearby.add(cam to d)
            }
        }

        val nearbyIds = nearby.mapTo(HashSet()) { it.first.id }
        stage.keys.retainAll(nearbyIds)
        minDist.keys.retainAll(nearbyIds)

        var emittedEvent: RadarAlertEvent? = null

        // 1. Check for PASSED or TURN-AWAY DISMISS on cameras currently in stage 1
        for ((cam, d) in nearby) {
            if (stage[cam.id] == 1) {
                val prevMin = minDist[cam.id] ?: d
                if (d < prevMin) {
                    minDist[cam.id] = d
                } else {
                    val didPassDirectly = prevMin <= passWithinM && d >= prevMin + passMarginM
                    if (didPassDirectly) {
                        stage[cam.id] = 2
                        if (emittedEvent == null) {
                            emittedEvent = RadarAlertEvent.Passed(cam, d)
                        }
                    } else {
                        // Turn-away fast dismiss: turned away > 60° or moved away before reaching 65m -> silently retract without "通過"
                        val bearing = GeoMath.bearingDeg(lat, lon, cam.lat, cam.lon)
                        val turnedAway = GeoMath.angleDiffDeg(headingDeg, bearing) > turnAwayDeg
                        val movedAway = d >= prevMin + turnAwayMarginM
                        if (turnedAway || movedAway) {
                            stage[cam.id] = 2
                        }
                    }
                }
            }
        }

        // 2. Find nearest camera within triggerDist & narrow corridor -> sync HUD expansion + single APPROACH alert
        val sortedNearby = nearby.sortedBy { it.second }
        var hudCamera: ActiveCameraHud? = null

        for ((cam, d) in sortedNearby) {
            val st = stage[cam.id] ?: 0
            if (st >= 2) continue

            val triggerDist = distConfig.distanceFor(cam, speedKmh)
            // HUD expansion is 100% synchronized with triggerDist (no 890m premature expansion!)
            if (d > triggerDist) continue

            if (!isInAheadCorridor(lat, lon, d, headingDeg, cam)) continue

            if (hudCamera == null) {
                hudCamera = ActiveCameraHud(cam, d.toInt())
            }

            if (st == 0 && speedKmh >= minSpeedKmh) {
                stage[cam.id] = 1
                minDist[cam.id] = d
                if (emittedEvent == null) {
                    emittedEvent = RadarAlertEvent.Approach(cam, d, cam.alertPhraseKeys())
                }
            }
        }

        return emittedEvent to hudCamera
    }

    private fun isInAheadCorridor(
        lat: Double,
        lon: Double,
        distanceM: Double,
        headingDeg: Float,
        cam: CameraPoint
    ): Boolean {
        val bearing = GeoMath.bearingDeg(lat, lon, cam.lat, cam.lon)
        val aheadAngleDiff = GeoMath.angleDiffDeg(headingDeg, bearing)

        // 1. Narrow forward cone (±28°)
        if (aheadAngleDiff > aheadToleranceDeg) {
            return false
        }

        // 2. Lateral corridor filter (<= 32m from vehicle's forward centerline)
        val lateralOffsetM = distanceM * sin(Math.toRadians(aheadAngleDiff.toDouble()))
        if (lateralOffsetM > maxLateralOffsetM) {
            return false
        }

        // 3. Camera shooting direction match (if known)
        val camHeading = cam.headingDeg
        if (camHeading != null) {
            val diff = GeoMath.angleDiffDeg(headingDeg, camHeading)
            if (cam.bidirectional) {
                val oppositeDiff = GeoMath.angleDiffDeg(headingDeg, (camHeading + 180f) % 360f)
                if (diff > directionToleranceDeg && oppositeDiff > directionToleranceDeg) {
                    return false
                }
            } else {
                if (diff > directionToleranceDeg) {
                    return false
                }
            }
        }
        return true
    }
}
