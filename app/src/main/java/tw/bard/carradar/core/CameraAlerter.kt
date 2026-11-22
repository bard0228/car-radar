package tw.bard.carradar.core

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
    private val aheadToleranceDeg: Float = 45f,
    private val directionToleranceDeg: Float = 60f,
    private val minSpeedKmh: Float = 10f,
    private val passWithinM: Double = 80.0,
    private val passMarginM: Double = 35.0,
    private val resetM: Double = 1000.0
) {
    // stage: 0/absent = none, 1 = approached (already alerted once), 2 = passed
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
        // Compute distances to all non-muted cameras within resetM
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

        // 1. Check for PASSED on cameras currently in stage 1
        for ((cam, d) in nearby) {
            if (stage[cam.id] == 1) {
                val prevMin = minDist[cam.id] ?: d
                if (d < prevMin) {
                    minDist[cam.id] = d
                } else if (prevMin <= passWithinM && d >= prevMin + passMarginM) {
                    stage[cam.id] = 2
                    if (emittedEvent == null) {
                        emittedEvent = RadarAlertEvent.Passed(cam, d)
                    }
                }
            }
        }

        // 2. Find nearest camera ahead for HUD and potential single APPROACH alert
        val sortedNearby = nearby.sortedBy { it.second }
        var hudCamera: ActiveCameraHud? = null

        for ((cam, d) in sortedNearby) {
            val st = stage[cam.id] ?: 0
            if (st >= 2) continue
            if (!isAheadAndMatchingDirection(lat, lon, headingDeg, cam)) continue

            if (hudCamera == null) {
                hudCamera = ActiveCameraHud(cam, d.toInt())
            }

            if (st == 0 && speedKmh >= minSpeedKmh) {
                val triggerDist = distConfig.distanceFor(cam, speedKmh)
                if (d <= triggerDist) {
                    stage[cam.id] = 1
                    minDist[cam.id] = d
                    if (emittedEvent == null) {
                        emittedEvent = RadarAlertEvent.Approach(cam, d, cam.alertPhraseKeys())
                    }
                }
            }
        }

        return emittedEvent to hudCamera
    }

    private fun isAheadAndMatchingDirection(
        lat: Double,
        lon: Double,
        headingDeg: Float?,
        cam: CameraPoint
    ): Boolean {
        if (headingDeg == null) return true
        val bearing = GeoMath.bearingDeg(lat, lon, cam.lat, cam.lon)
        if (GeoMath.angleDiffDeg(headingDeg, bearing) > aheadToleranceDeg) {
            return false
        }
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
