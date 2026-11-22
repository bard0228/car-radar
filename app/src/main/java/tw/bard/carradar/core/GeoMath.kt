package tw.bard.carradar.core

import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360.0) % 360.0).toFloat()
    }

    fun angleDiffDeg(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /**
     * TWD97 (TM2, central meridian 121°E) -> WGS84 (lat, lon)
     */
    fun twd97ToWgs84(x: Double, y: Double): Pair<Double, Double> {
        val a = 6378137.0
        val b = 6356752.314245
        val lon0 = Math.toRadians(121.0)
        val k0 = 0.9999
        val dx = 250000.0

        val e = sqrt(1.0 - (b * b) / (a * a))
        val e2 = (e * e) / (1.0 - e * e)

        val xAdj = x - dx
        val m = y / k0
        val mu = m / (a * (1.0 - e.pow(2) / 4.0 - 3.0 * e.pow(4) / 64.0 - 5.0 * e.pow(6) / 256.0))

        val e1 = (1.0 - sqrt(1.0 - e * e)) / (1.0 + sqrt(1.0 - e * e))
        val j1 = 3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0
        val j2 = 21.0 * e1.pow(2) / 16.0 - 55.0 * e1.pow(4) / 32.0
        val j3 = 151.0 * e1.pow(3) / 96.0
        val j4 = 1097.0 * e1.pow(4) / 512.0

        val fp = mu + j1 * sin(2 * mu) + j2 * sin(4 * mu) + j3 * sin(6 * mu) + j4 * sin(8 * mu)

        val c1 = e2 * cos(fp).pow(2)
        val t1 = tan(fp).pow(2)
        val r1 = a * (1.0 - e * e) / (1.0 - e * e * sin(fp).pow(2)).pow(1.5)
        val n1 = a / sqrt(1.0 - e * e * sin(fp).pow(2))
        val d = xAdj / (n1 * k0)

        val q1 = n1 * tan(fp) / r1
        val q2 = d * d / 2.0
        val q3 = (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * e2) * d.pow(4) / 24.0
        val q4 = (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 3.0 * c1 * c1 - 252.0 * e2) * d.pow(6) / 720.0
        val lat = fp - q1 * (q2 - q3 + q4)

        val q5 = d
        val q6 = (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0
        val q7 = (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * e2 + 24.0 * t1 * t1) * d.pow(5) / 120.0
        val lon = lon0 + (q5 - q6 + q7) / cos(fp)

        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }
}
