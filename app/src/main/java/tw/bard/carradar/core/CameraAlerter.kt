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
    private val directionToleranceDeg: Float = 65f,
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

    // Expressway / Elevated road state machine
    var isExpresswayMode: Boolean = false
        private set
    private var inExpresswayCorridor: Boolean = false
    private var fastSinceMs: Long = 0L
    private var slowSinceMs: Long = 0L

    var isMuted: (CameraPoint) -> Boolean = { false }
    var distConfig: AlertDistancesConfig = AlertDistancesConfig()

    fun reset() {
        stage.clear()
        minDist.clear()
        isExpresswayMode = false
        inExpresswayCorridor = false
        fastSinceMs = 0L
        slowSinceMs = 0L
    }

    fun update(
        cameras: List<CameraPoint>,
        lat: Double,
        lon: Double,
        speedKmh: Float,
        headingDeg: Float?,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<RadarAlertEvent?, ActiveCameraHud?> {
        updateExpresswayMode(speedKmh, nowMs)

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
                        if (cam.roadLevel == RoadLevel.EXPRESSWAY) {
                            isExpresswayMode = true
                            inExpresswayCorridor = true
                            slowSinceMs = 0L
                        }
                        if (emittedEvent == null) {
                            emittedEvent = RadarAlertEvent.Passed(cam, d)
                        }
                    } else {
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

        // 2. Find nearest eligible camera within triggerDist & narrow corridor
        val sortedNearby = nearby.sortedBy { it.second }
        var hudCamera: ActiveCameraHud? = null

        for ((cam, d) in sortedNearby) {
            val st = stage[cam.id] ?: 0
            if (st >= 2) continue

            // Filter out surface-road cameras when driving on an expressway/elevated road
            if (shouldSuppressSurfaceCamera(cam)) continue

            val triggerDist = distConfig.distanceFor(cam, speedKmh)
            if (d > triggerDist) continue

            if (!isInAheadCorridor(lat, lon, d, headingDeg, cam)) continue

            if (cam.roadLevel == RoadLevel.EXPRESSWAY && d <= 200.0 && speedKmh >= 65f) {
                inExpresswayCorridor = true
                isExpresswayMode = true
            }

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

    private fun updateExpresswayMode(speedKmh: Float, nowMs: Long) {
        if (speedKmh >= 75f) {
            slowSinceMs = 0L
            if (fastSinceMs == 0L) fastSinceMs = nowMs
            if (nowMs - fastSinceMs >= 5_000L) {
                isExpresswayMode = true
            }
        } else {
            fastSinceMs = 0L
            if (speedKmh < 55f) {
                if (slowSinceMs == 0L) slowSinceMs = nowMs
                if (nowMs - slowSinceMs >= 6_000L) {
                    isExpresswayMode = false
                    inExpresswayCorridor = false
                }
            } else {
                slowSinceMs = 0L
            }
        }
    }

    /**
     * In Expressway Mode:
     * - Never suppress EXPRESSWAY cameras (even 60 km/h off-ramp cameras).
     * - Suppress SURFACE red-light / pure tech enforcement (no speed limit) and <= 50 km/h city cameras.
     * - If confirmed inside an expressway corridor, also suppress SURFACE 60 km/h cameras directly underneath.
     */
    private fun shouldSuppressSurfaceCamera(cam: CameraPoint): Boolean {
        if (!isExpresswayMode) return false
        if (cam.roadLevel == RoadLevel.EXPRESSWAY) return false
        if (cam.limitKmh <= 0) return true // Pure red light or intersection tech enforcement
        if (cam.limitKmh <= 50) return true // City surface street speed camera
        if (inExpresswayCorridor && cam.limitKmh <= 60) return true
        return false
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
