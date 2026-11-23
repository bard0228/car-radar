package tw.bard.carradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.core.GeoMath
import tw.bard.carradar.core.IntervalZone
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class CameraRepository(private val context: Context) {

    private val cachedCamerasFile = File(context.applicationContext.filesDir, "cameras_online.json")

    suspend fun loadCameras(): List<CameraPoint> = withContext(Dispatchers.IO) {
        val result = ArrayList<CameraPoint>(2400)

        // 0. Load user-created custom cameras first (never overwritten by online sync)
        result.addAll(CustomCameraStore(context).listAll())

        // 1. Load fixed speed cameras (prefer online updated JSON if present)
        val camJsonStr = if (cachedCamerasFile.exists()) {
            cachedCamerasFile.readText(Charsets.UTF_8)
        } else {
            context.assets.open("cameras.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        result.addAll(parseCamerasJsonStatic(camJsonStr))

        // 2. Load enforcement CSVs from assets/enforcement/
        val csvFiles = context.assets.list("enforcement") ?: emptyArray()
        for (name in csvFiles) {
            if (!name.endsWith(".csv", ignoreCase = true)) continue
            runCatching {
                val text = context.assets.open("enforcement/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseEnforcementCsv(name, text, result)
            }
        }
        result
    }

    suspend fun loadZones(): List<IntervalZone> = withContext(Dispatchers.IO) {
        val jsonStr = context.assets.open("zones.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseZonesJson(jsonStr)
    }

    /**
     * One-tap online refresh from Taiwan Open Data (dataset 7320 / MOI API).
     */
    suspend fun refreshFromOpenData(prefs: Prefs): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/E7893FD4-EBB1-4949-B42A-F3978085D7D6/download")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CarRadar/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            val csv = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = parseMoiCsvToJson(csv)
            if (parsed.first < 500) {
                error("資料筆數異常 (${parsed.first})")
            }
            cachedCamerasFile.writeText(parsed.second, Charsets.UTF_8)
            prefs.lastDataSyncMs = System.currentTimeMillis()
            parsed.first
        }
    }

    private fun parseEnforcementCsv(fileName: String, text: String, out: MutableList<CameraPoint>) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return
        val headers = splitCsvLine(lines[0])
        val lonIdx = headers.indexOfFirst { it.contains("經度") || it.equals("lon", true) || it.equals("x", true) }
        val latIdx = headers.indexOfFirst { it.contains("緯度") || it.equals("lat", true) || it.equals("y", true) }
        if (lonIdx < 0 || latIdx < 0) return

        val addrIdx = headers.indexOfFirst { it.contains("地點") || it.contains("位置") || it.contains("地址") || it.contains("路口") }
        val itemIdx = headers.indexOfFirst { it.contains("取締") || it.contains("項目") || it.contains("違規") || it.contains("備註") }
        val limitIdx = headers.indexOfFirst { it.contains("速限") || it.contains("限速") }

        for (r in 1 until lines.size) {
            val cols = splitCsvLine(lines[r])
            if (cols.size <= maxOf(lonIdx, latIdx)) continue
            val rawLon = cols[lonIdx].trim().toDoubleOrNull() ?: continue
            val rawLat = cols[latIdx].trim().toDoubleOrNull() ?: continue
            val (lat, lon) = normalizeCoord(rawLat, rawLon) ?: continue

            val addr = if (addrIdx in cols.indices) cols[addrIdx].trim() else ""
            val itemText = if (itemIdx in cols.indices) cols[itemIdx].trim() else ""
            val limit = if (limitIdx in cols.indices) Regex("\\d+").find(cols[limitIdx])?.value?.toIntOrNull() ?: 0 else 0

            val combined = "$itemText $addr"
            val kind = when {
                limit > 0 -> CameraKind.SPEED
                combined.contains("闖紅燈") -> CameraKind.RED_LIGHT
                else -> CameraKind.TECH
            }
            out.add(
                CameraPoint(
                    id = "enf_${fileName}_${r}",
                    lat = lat,
                    lon = lon,
                    limitKmh = limit,
                    directionText = "",
                    address = addr.ifBlank { "科技執法點位" },
                    city = "",
                    kind = kind,
                    headingDeg = null,
                    bidirectional = true,
                    techDetail = itemText
                )
            )
        }
    }

    private fun parseMoiCsvToJson(csv: String): Pair<Int, String> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return 0 to "{}"
        val headers = splitCsvLine(lines[0])
        val latIdx = headers.indexOfFirst { it.contains("Latitude", true) || it.contains("緯度") }
        val lonIdx = headers.indexOfFirst { it.contains("Longitude", true) || it.contains("經度") }
        val limitIdx = headers.indexOfFirst { it.contains("limit", true) || it.contains("速限") }
        val dirIdx = headers.indexOfFirst { it.contains("direct", true) || it.contains("方向") }
        val addrIdx = headers.indexOfFirst { it.contains("Address", true) || it.contains("地點") || it.contains("地址") }
        val cityIdx = headers.indexOfFirst { it.contains("City", true) || it.contains("縣市") }
        val regionIdx = headers.indexOfFirst { it.contains("Region", true) || it.contains("鄉鎮") }

        if (latIdx < 0 || lonIdx < 0) return 0 to "{}"
        val arr = JSONArray()
        for (i in 1 until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size <= maxOf(latIdx, lonIdx)) continue
            val lat = cols[latIdx].trim().toDoubleOrNull() ?: continue
            val lon = cols[lonIdx].trim().toDoubleOrNull() ?: continue
            if (lat !in 20.0..27.0 || lon !in 117.0..124.0) continue
            val limit = if (limitIdx in cols.indices) Regex("\\d+").find(cols[limitIdx])?.value?.toIntOrNull() ?: 0 else 0
            val dir = if (dirIdx in cols.indices) cols[dirIdx].trim() else ""
            val addr = if (addrIdx in cols.indices) cols[addrIdx].trim() else ""
            val city = if (cityIdx in cols.indices) cols[cityIdx].trim() else ""
            val town = if (regionIdx in cols.indices) cols[regionIdx].trim() else ""

            val item = JSONArray()
            item.put(lat)
            item.put(lon)
            item.put(limit)
            item.put(dir)
            item.put(addr)
            item.put(city)
            item.put(town)
            arr.put(item)
        }
        val root = JSONObject()
        root.put("cameras", arr)
        return arr.length() to root.toString()
    }

    private fun normalizeCoord(latOrY: Double, lonOrX: Double): Pair<Double, Double>? {
        if (latOrY in 20.0..27.0 && lonOrX in 117.0..124.0) {
            return latOrY to lonOrX
        }
        if (lonOrX in 100000.0..400000.0 && latOrY in 2400000.0..2900000.0) {
            val (lat, lon) = GeoMath.twd97ToWgs84(lonOrX, latOrY)
            if (lat in 20.0..27.0 && lon in 117.0..124.0) return lat to lon
        }
        return null
    }

    private fun parseDirection(dir: String): Pair<Float?, Boolean> {
        val d = dir.trim()
        if (d.isEmpty() || d.contains("雙向") || d.contains("多向")) {
            val base = when {
                d.contains("南北") -> 0f
                d.contains("東西") -> 90f
                else -> null
            }
            return base to true
        }
        val heading = when {
            d.contains("北") && d.contains("東") -> 45f
            d.contains("南") && d.contains("東") -> 135f
            d.contains("南") && d.contains("西") -> 225f
            d.contains("北") && d.contains("西") -> 315f
            d.contains("北") -> 0f
            d.contains("東") -> 90f
            d.contains("南") -> 180f
            d.contains("西") -> 270f
            else -> null
        }
        return heading to (heading == null)
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    companion object {
        fun parseZonesJson(jsonStr: String): List<IntervalZone> {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("zones") ?: return emptyList()
            val list = ArrayList<IntervalZone>(arr.length())
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val a = o.optJSONArray("a") ?: return@runCatching
                    val b = o.optJSONArray("b") ?: return@runCatching
                    if (a.length() < 2 || b.length() < 2) return@runCatching
                    val head = if (o.isNull("head")) null else o.optDouble("head").toFloat()
                    list.add(
                        IntervalZone(
                            id = o.optString("id", "zone_$i"),
                            name = o.optString("name", "區間測速"),
                            limitKmh = o.optInt("limit", 60),
                            lenM = o.optInt("len", 0),
                            aLat = a.getDouble(0),
                            aLon = a.getDouble(1),
                            bLat = b.getDouble(0),
                            bLon = b.getDouble(1),
                            headingDeg = head,
                            bidirectional = o.optBoolean("both", true)
                        )
                    )
                }
            }
            return list
        }

        fun parseCamerasJsonStatic(jsonStr: String): List<CameraPoint> {
            val out = ArrayList<CameraPoint>(2000)
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("cameras") ?: return out
            for (i in 0 until arr.length()) {
                runCatching {
                    val row = arr.getJSONArray(i)
                    if (row.length() < 2) return@runCatching
                    val lat = row.getDouble(0)
                    val lon = row.getDouble(1)
                    val limit = row.optInt(2, 0)
                    val dir = row.optString(3, "")
                    val addr = row.optString(4, "")
                    val city = row.optString(5, "")
                    val town = row.optString(6, "")
                    val (heading, bidir) = parseDirectionStatic(dir)
                    val kind = when {
                        limit > 0 -> CameraKind.SPEED
                        addr.contains("闖紅燈") -> CameraKind.RED_LIGHT
                        else -> CameraKind.SPEED
                    }
                    out.add(
                        CameraPoint(
                            id = "cam_${i}_${"%.5f".format(lat)}_${"%.5f".format(lon)}",
                            lat = lat,
                            lon = lon,
                            limitKmh = limit,
                            directionText = dir,
                            address = if (town.isNotBlank() && !addr.contains(town)) "$town$addr" else addr,
                            city = city,
                            kind = kind,
                            headingDeg = heading,
                            bidirectional = bidir
                        )
                    )
                }
            }
            return out
        }

        private fun parseDirectionStatic(dir: String): Pair<Float?, Boolean> {
            val d = dir.trim()
            if (d.isEmpty() || d.contains("雙向") || d.contains("多向")) {
                val base = when {
                    d.contains("南北") -> 0f
                    d.contains("東西") -> 90f
                    else -> null
                }
                return base to true
            }
            val heading = when {
                d.contains("北") && d.contains("東") -> 45f
                d.contains("南") && d.contains("東") -> 135f
                d.contains("南") && d.contains("西") -> 225f
                d.contains("北") && d.contains("西") -> 315f
                d.contains("北") -> 0f
                d.contains("東") -> 90f
                d.contains("南") -> 180f
                d.contains("西") -> 270f
                else -> null
            }
            return heading to (heading == null)
        }
    }
}
