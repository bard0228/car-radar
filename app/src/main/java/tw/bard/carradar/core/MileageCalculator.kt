package tw.bard.carradar.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MileageRecord(
    val id: String,
    val date: String,       // YYYY-MM-DD (Asia/Taipei)
    val startTime: String,  // HH:mm
    val endTime: String,    // HH:mm
    val distanceKm: Double,
    val startEpochMs: Long,
    val endEpochMs: Long
)

class MileageCalculator(
    private val minStepMeters: Double = 5.0,
    private val maxReasonableSpeedMs: Double = 70.0 // ~252 km/h sanity cap against GPS teleport
) {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimeMs: Long = 0L
    var totalMeters: Double = 0.0
        private set
    var sessionStartMs: Long = 0L
        private set

    fun startSession(nowMs: Long = System.currentTimeMillis()) {
        lastLat = null
        lastLon = null
        lastTimeMs = 0L
        totalMeters = 0.0
        sessionStartMs = nowMs
    }

    fun onLocation(lat: Double, lon: Double, speedKmh: Float, nowMs: Long = System.currentTimeMillis()): Double {
        val prevLat = lastLat
        val prevLon = lastLon
        val prevMs = lastTimeMs

        lastLat = lat
        lastLon = lon
        lastTimeMs = nowMs

        if (prevLat == null || prevLon == null || prevMs <= 0L) {
            return totalMeters / 1000.0
        }

        val dtSec = ((nowMs - prevMs).coerceAtLeast(1L)) / 1000.0
        val distM = GeoMath.haversineMeters(prevLat, prevLon, lat, lon)

        // Filter out stationary GPS drift and impossible teleports
        if (distM >= minStepMeters && speedKmh >= 2.0f && (distM / dtSec) <= maxReasonableSpeedMs) {
            totalMeters += distM
        }
        return totalMeters / 1000.0
    }

    fun currentKm(): Double = totalMeters / 1000.0

    fun buildRecord(endNowMs: Long = System.currentTimeMillis()): MileageRecord? {
        val km = currentKm()
        if (km < 0.05) return null // Ignore < 50m accidental taps
        val startMs = if (sessionStartMs > 0L) sessionStartMs else endNowMs
        val taipei = ZoneId.of("Asia/Taipei")
        val startZdt = Instant.ofEpochMilli(startMs).atZone(taipei)
        val endZdt = Instant.ofEpochMilli(endNowMs).atZone(taipei)

        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        return MileageRecord(
            id = "${startMs}_$endNowMs",
            date = startZdt.format(dateFmt),
            startTime = startZdt.format(timeFmt),
            endTime = endZdt.format(timeFmt),
            distanceKm = kotlin.math.round(km * 100.0) / 100.0,
            startEpochMs = startMs,
            endEpochMs = endNowMs
        )
    }
}
