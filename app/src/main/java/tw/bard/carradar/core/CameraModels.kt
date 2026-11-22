package tw.bard.carradar.core

enum class CameraKind {
    SPEED,       // 固定式測速照相 (有速限)
    RED_LIGHT,   // 闖紅燈照相 (無速限)
    TECH         // 科技執法 (無速限，統稱)
}

data class CameraPoint(
    val id: String,
    val lat: Double,
    val lon: Double,
    val limitKmh: Int,
    val directionText: String,
    val address: String,
    val city: String,
    val kind: CameraKind,
    val headingDeg: Float? = null,
    val bidirectional: Boolean = true,
    val techDetail: String = ""
) {
    /**
     * 方案甲：
     * - 有速限 -> 只報速限數字 ("30".."110")
     * - 無速限闖紅燈 -> "red_light"
     * - 無速限科技執法 -> "tech"
     */
    fun alertPhraseKeys(): List<String> {
        if (limitKmh > 0) {
            return listOf(snapSpeedLimit(limitKmh).toString())
        }
        return when (kind) {
            CameraKind.RED_LIGHT -> listOf("red_light")
            CameraKind.TECH -> listOf("tech")
            CameraKind.SPEED -> listOf("60")
        }
    }

    fun displayLabel(): String {
        if (limitKmh > 0) return limitKmh.toString()
        return when (kind) {
            CameraKind.RED_LIGHT -> "闖紅燈"
            CameraKind.TECH -> "科技執法"
            CameraKind.SPEED -> "測速"
        }
    }

    companion object {
        private val SUPPORTED_SPEEDS = intArrayOf(30, 40, 50, 60, 70, 80, 90, 100, 110)

        fun snapSpeedLimit(limitKmh: Int): Int {
            var best = 60
            var bestDiff = Int.MAX_VALUE
            for (s in SUPPORTED_SPEEDS) {
                val d = kotlin.math.abs(s - limitKmh)
                if (d < bestDiff) {
                    bestDiff = d
                    best = s
                }
            }
            return best
        }
    }
}

data class IntervalZone(
    val id: String,
    val name: String,
    val limitKmh: Int,
    val lenM: Int,
    val aLat: Double,
    val aLon: Double,
    val bLat: Double,
    val bLon: Double,
    val headingDeg: Float? = null,
    val bidirectional: Boolean = true
) {
    fun entryPhraseKeys(): List<String> {
        val speedKey = CameraPoint.snapSpeedLimit(if (limitKmh > 0) limitKmh else 60).toString()
        return listOf("interval", speedKey)
    }
}

data class AlertDistancesConfig(
    val lowSpeedDistM: Int = 300,
    val midSpeedDistM: Int = 500,
    val highSpeedDistM: Int = 500,
    val techDistM: Int = 250
) {
    fun distanceFor(camera: CameraPoint, speedKmh: Float): Double {
        if (camera.limitKmh <= 0) {
            return techDistM.toDouble()
        }
        return when {
            speedKmh < 60f -> lowSpeedDistM.toDouble()
            speedKmh < 90f -> midSpeedDistM.toDouble()
            else -> highSpeedDistM.toDouble()
        }
    }
}
