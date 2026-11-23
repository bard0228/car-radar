package tw.bard.carradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.core.CameraPoint
import java.io.File

/**
 * Persists user-created map cameras in a dedicated file (custom_cameras.json)
 * completely isolated from open-data refreshes.
 */
class CustomCameraStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "custom_cameras.json")
    private val lock = Any()

    fun listAll(): List<CameraPoint> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            deserialize(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun add(
        lat: Double,
        lon: Double,
        kind: CameraKind,
        limitKmh: Int,
        address: String
    ): CameraPoint = synchronized(lock) {
        val current = listAll().toMutableList()
        val id = "custom_${System.currentTimeMillis()}"
        val cleanLimit = if (kind == CameraKind.SPEED) limitKmh.coerceIn(30, 110) else 0
        val cam = CameraPoint(
            id = id,
            lat = lat,
            lon = lon,
            limitKmh = cleanLimit,
            directionText = "雙向",
            address = address.ifBlank { "自訂點位" },
            city = "自訂",
            kind = kind,
            headingDeg = null,
            bidirectional = true,
            techDetail = if (kind == CameraKind.TECH) "自訂科技執法" else ""
        )
        current.add(0, cam)
        file.writeText(serialize(current), Charsets.UTF_8)
        cam
    }

    fun delete(id: String) = synchronized(lock) {
        val filtered = listAll().filterNot { it.id == id }
        file.writeText(serialize(filtered), Charsets.UTF_8)
    }

    companion object {
        const val ID_PREFIX = "custom_"

        fun serialize(cameras: List<CameraPoint>): String {
            val arr = JSONArray()
            for (c in cameras) {
                val o = JSONObject()
                o.put("id", c.id)
                o.put("lat", c.lat)
                o.put("lon", c.lon)
                o.put("limitKmh", c.limitKmh)
                o.put("kind", c.kind.name)
                o.put("address", c.address)
                arr.put(o)
            }
            return arr.toString()
        }

        fun deserialize(json: String): List<CameraPoint> {
            val arr = JSONArray(json)
            val out = ArrayList<CameraPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching {
                    CameraKind.valueOf(o.optString("kind", CameraKind.SPEED.name))
                }.getOrDefault(CameraKind.SPEED)
                out.add(
                    CameraPoint(
                        id = o.getString("id"),
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        limitKmh = o.optInt("limitKmh", 0),
                        directionText = "雙向",
                        address = o.optString("address", "自訂點位"),
                        city = "自訂",
                        kind = kind,
                        headingDeg = null,
                        bidirectional = true,
                        techDetail = if (kind == CameraKind.TECH) "自訂科技執法" else ""
                    )
                )
            }
            return out
        }
    }
}
