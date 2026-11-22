package tw.bard.carradar.core

sealed class IntervalAlertEvent {
    abstract val zone: IntervalZone
    abstract val phraseKeys: List<String>

    data class Entered(
        override val zone: IntervalZone,
        override val phraseKeys: List<String>
    ) : IntervalAlertEvent()

    data class Exited(
        override val zone: IntervalZone,
        override val phraseKeys: List<String> = listOf("pass")
    ) : IntervalAlertEvent()
}

class IntervalTracker(
    private val entryRadiusM: Double = 85.0,
    private val exitRadiusM: Double = 100.0,
    private val minSpeedKmh: Float = 15f
) {
    private var activeZone: IntervalZone? = null
    private var enteredFromA: Boolean = true
    private var activeMinExitDistM: Double = Double.MAX_VALUE
    private val cooldownZones = HashSet<String>()

    fun reset() {
        activeZone = null
        activeMinExitDistM = Double.MAX_VALUE
        cooldownZones.clear()
    }

    fun activeZoneInfo(): IntervalZone? = activeZone

    fun update(
        zones: List<IntervalZone>,
        lat: Double,
        lon: Double,
        speedKmh: Float
    ): IntervalAlertEvent? {
        val current = activeZone
        if (current != null) {
            val exitLat = if (enteredFromA) current.bLat else current.aLat
            val exitLon = if (enteredFromA) current.bLon else current.aLon
            val distToExit = GeoMath.haversineMeters(lat, lon, exitLat, exitLon)

            if (distToExit <= exitRadiusM) {
                if (distToExit < activeMinExitDistM) {
                    activeMinExitDistM = distToExit
                } else if (distToExit >= activeMinExitDistM + 20.0) {
                    activeZone = null
                    activeMinExitDistM = Double.MAX_VALUE
                    cooldownZones.add(current.id)
                    return IntervalAlertEvent.Exited(current)
                }
            } else if (activeMinExitDistM <= exitRadiusM) {
                activeZone = null
                activeMinExitDistM = Double.MAX_VALUE
                cooldownZones.add(current.id)
                return IntervalAlertEvent.Exited(current)
            }
            return null
        }

        if (speedKmh < minSpeedKmh) return null

        for (zone in zones) {
            val dA = GeoMath.haversineMeters(lat, lon, zone.aLat, zone.aLon)
            val dB = GeoMath.haversineMeters(lat, lon, zone.bLat, zone.bLon)

            if (cooldownZones.contains(zone.id)) {
                if (dA > 300.0 && dB > 300.0) {
                    cooldownZones.remove(zone.id)
                }
                continue
            }

            if (dA <= entryRadiusM) {
                activeZone = zone
                enteredFromA = true
                activeMinExitDistM = Double.MAX_VALUE
                return IntervalAlertEvent.Entered(zone, zone.entryPhraseKeys())
            }
            if (zone.bidirectional && dB <= entryRadiusM) {
                activeZone = zone
                enteredFromA = false
                activeMinExitDistM = Double.MAX_VALUE
                return IntervalAlertEvent.Entered(zone, zone.entryPhraseKeys())
            }
        }
        return null
    }
}
