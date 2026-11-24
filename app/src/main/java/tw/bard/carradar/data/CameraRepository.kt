package tw.bard.carradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.core.GeoMath
import tw.bard.carradar.core.IntervalZone
import tw.bard.carradar.core.RoadLevel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class SyncSummary(
    val fixedCount: Int,
    val techCount: Int,
    val customCount: Int
)

class CameraRepository(private val context: Context) {

    private val cachedCamerasFile = File(context.applicationContext.filesDir, "cameras_online.json")
    private val onlineEnfDir = File(context.applicationContext.filesDir, "enforcement_online").apply { mkdirs() }

    suspend fun loadCameras(): List<CameraPoint> = withContext(Dispatchers.IO) {
        val customList = CustomCameraStore(context).listAll()

        // 1. Load fixed speed cameras (prefer online updated JSON if present)
        val camJsonStr = if (cachedCamerasFile.exists()) {
            cachedCamerasFile.readText(Charsets.UTF_8)
        } else {
            context.assets.open("cameras.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val fixedList = parseCamerasJsonStatic(camJsonStr)

        // 2. Load enforcement CSVs (prefer online refreshed CSV per file if present)
        val enfList = ArrayList<CameraPoint>(2200)
        val csvFiles = context.assets.list("enforcement") ?: emptyArray()
        for (name in csvFiles) {
            if (!name.endsWith(".csv", ignoreCase = true)) continue
            runCatching {
                val onlineFile = File(onlineEnfDir, name)
                val bytes = if (onlineFile.exists() && onlineFile.length() > 32) {
                    onlineFile.readBytes()
                } else {
                    context.assets.open("enforcement/$name").use { it.readBytes() }
                }
                val text = decodeBytesSmart(bytes)
                enfList.addAll(parseEnforcementCsvStatic(name, text))
            }
        }

        // 3. Deduplicate & merge overlapping open-data points within 30m, always preserving custom points untouched
        val mergedOpenData = mergeNearbyCameras(fixedList, enfList)
        val result = ArrayList<CameraPoint>(customList.size + mergedOpenData.size)
        result.addAll(customList)
        result.addAll(mergedOpenData)
        result
    }

    suspend fun loadZones(): List<IntervalZone> = withContext(Dispatchers.IO) {
        val jsonStr = context.assets.open("zones.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseZonesJson(jsonStr)
    }

    /**
     * One-tap full sync: MOI fixed speed cameras + all refreshable county enforcement CSVs in sources.json.
     */
    suspend fun refreshFromOpenData(prefs: Prefs): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Refresh MOI fixed speed cameras
            val moiUrl = URL("https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/E7893FD4-EBB1-4949-B42A-F3978085D7D6/download")
            val moiBytes = downloadBytes(moiUrl)
            val moiText = decodeBytesSmart(moiBytes)
            val parsed = parseMoiCsvToJson(moiText)
            if (parsed.first < 500) {
                error("警政署主檔筆數異常 (${parsed.first})")
            }
            cachedCamerasFile.writeText(parsed.second, Charsets.UTF_8)

            // 2. Concurrently refresh county enforcement CSVs from assets/enforcement/sources.json
            runCatching {
                val srcJson = context.assets.open("enforcement/sources.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val sources = JSONObject(srcJson).optJSONArray("sources")
                if (sources != null) {
                    coroutineScope {
                        val jobs = (0 until sources.length()).mapNotNull { idx ->
                            val o = sources.optJSONObject(idx) ?: return@mapNotNull null
                            if (!o.optBoolean("refresh", false)) return@mapNotNull null
                            val fileName = o.optString("file", "")
                            val urlStr = o.optString("url", "")
                            if (fileName.isBlank() || urlStr.isBlank()) return@mapNotNull null
                            async(Dispatchers.IO) {
                                runCatching {
                                    val bytes = downloadBytes(URL(urlStr), timeoutMs = 8_000)
                                    val text = decodeBytesSmart(bytes)
                                    val pts = parseEnforcementCsvStatic(fileName, text)
                                    if (pts.isNotEmpty()) {
                                        File(onlineEnfDir, fileName).writeBytes(bytes)
                                    }
                                }
                            }
                        }
                        jobs.awaitAll()
                    }
                }
            }

            prefs.lastDataSyncMs = System.currentTimeMillis()

            // Count final parsed totals
            val allLoaded = loadCameras()
            val customCount = CustomCameraStore(context).listAll().size
            val fixedCount = parsed.first
            val techCount = (allLoaded.size - customCount - fixedCount).coerceAtLeast(0)
            SyncSummary(fixedCount = fixedCount, techCount = techCount, customCount = customCount)
        }
    }

    private fun downloadBytes(url: URL, timeoutMs: Int = 15_000): ByteArray {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarRadar/1.3")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            error("HTTP $code")
        }
        return conn.inputStream.use { it.readBytes() }
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

    companion object {
        private val EXPRESSWAY_REGEX = Regex(
            "(國道|快速道路|快速公路|高架|匝道|環快|市民大道高架|建國高架|新生高架|洲美|鼎金|大中快速|台61|臺61|台62|臺62|台64|臺64|台65|臺65|台66|臺66|台68|臺68|台72|臺72|台74|臺74|台76|臺76|台78|臺78|台82|臺82|台84|臺84|台86|臺86|台88|臺88)"
        )

        fun classifyRoadLevel(address: String, limitKmh: Int): RoadLevel {
            if (EXPRESSWAY_REGEX.containsMatchIn(address)) {
                return RoadLevel.EXPRESSWAY
            }
            if (limitKmh >= 80) {
                return RoadLevel.EXPRESSWAY
            }
            return RoadLevel.SURFACE
        }

        /**
         * Smartly decodes UTF-8 (stripping BOM) or falls back to Big5 / MS950 for Taiwan county CSVs.
         */
        fun decodeBytesSmart(bytes: ByteArray): String {
            val utf8Decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val utf8 = runCatching {
                utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (utf8 != null) {
                return utf8.removePrefix("\uFEFF")
            }
            val cp950 = runCatching { Charset.forName("MS950") }.getOrElse { Charset.forName("Big5") }
            return String(bytes, cp950).removePrefix("\uFEFF")
        }

        fun parseEnforcementCsvStatic(fileName: String, text: String): List<CameraPoint> {
            val out = ArrayList<CameraPoint>()
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size < 2) return out

            // Scan up to first 6 lines to locate the true header row (some county CSVs have title banners on row 0)
            var headerRowIdx = -1
            var lonIdx = -1
            var latIdx = -1
            var headers: List<String> = emptyList()

            val maxScan = minOf(6, lines.size - 1)
            for (hRow in 0 until maxScan) {
                val cols = splitCsvLine(lines[hRow]).map { it.trim().removePrefix("\uFEFF") }
                val lLon = cols.indexOfFirst { isLonHeader(it) }
                val lLat = cols.indexOfFirst { isLatHeader(it) }
                if (lLon >= 0 && lLat >= 0 && lLon != lLat) {
                    headerRowIdx = hRow
                    lonIdx = lLon
                    latIdx = lLat
                    headers = cols
                    break
                }
            }
            if (headerRowIdx < 0) return out

            val addrIdx = headers.indexOfFirst {
                it.contains("地點") || it.contains("位置") || it.contains("地址") ||
                    it.contains("路口") || it.contains("路段") || it.equals("address", true)
            }
            val itemIdx = headers.indexOfFirst {
                it.contains("取締") || it.contains("項目") || it.contains("違規") ||
                    it.contains("備註") || it.contains("violation", true) || it.equals("remark", true)
            }
            val dirIdx = headers.indexOfFirst {
                it.contains("方向") || it.contains("行向") || it.equals("direct", true)
            }
            val limitIdx = headers.indexOfFirst {
                it.contains("速限") || it.contains("限速") || it.equals("limit", true)
            }

            for (r in (headerRowIdx + 1) until lines.size) {
                val cols = splitCsvLine(lines[r])
                if (cols.size <= maxOf(lonIdx, latIdx)) continue
                val rawLon = extractDouble(cols[lonIdx]) ?: continue
                val rawLat = extractDouble(cols[latIdx]) ?: continue
                val (lat, lon) = normalizeCoord(rawLat, rawLon) ?: continue

                val addr = if (addrIdx in cols.indices) cols[addrIdx].trim() else ""
                val itemText = if (itemIdx in cols.indices) cols[itemIdx].trim() else ""
                val dirText = if (dirIdx in cols.indices) cols[dirIdx].trim() else ""
                val limit = if (limitIdx in cols.indices) Regex("\\d+").find(cols[limitIdx])?.value?.toIntOrNull() ?: 0 else 0

                val (heading, bidir) = parseDirectionStatic(dirText)
                val combined = "$itemText $addr"
                val kind = when {
                    limit > 0 -> CameraKind.SPEED
                    combined.contains("闖紅燈") -> CameraKind.RED_LIGHT
                    else -> CameraKind.TECH
                }
                val cleanAddr = addr.ifBlank { "科技執法點位" }
                out.add(
                    CameraPoint(
                        id = "enf_${fileName}_$r",
                        lat = lat,
                        lon = lon,
                        limitKmh = limit,
                        directionText = dirText,
                        address = cleanAddr,
                        city = "",
                        kind = kind,
                        headingDeg = heading,
                        bidirectional = bidir,
                        techDetail = itemText,
                        roadLevel = classifyRoadLevel(cleanAddr, limit)
                    )
                )
            }
            return out
        }

        /**
         * Spatial deduplication: merges enforcement CSV points that overlap within 30m of a fixed camera
         * so intersections don't double-alert, while preserving speed limit and EXPRESSWAY level.
         */
        fun mergeNearbyCameras(fixed: List<CameraPoint>, enf: List<CameraPoint>): List<CameraPoint> {
            val merged = ArrayList<CameraPoint>(fixed.size + enf.size)
            merged.addAll(fixed)
            for (e in enf) {
                var dupIdx = -1
                for (i in merged.indices) {
                    val existing = merged[i]
                    if (kotlin.math.abs(existing.lat - e.lat) > 0.0004 || kotlin.math.abs(existing.lon - e.lon) > 0.0004) {
                        continue
                    }
                    if (GeoMath.haversineMeters(existing.lat, existing.lon, e.lat, e.lon) <= 30.0) {
                        dupIdx = i
                        break
                    }
                }
                if (dupIdx < 0) {
                    merged.add(e)
                } else {
                    val ex = merged[dupIdx]
                    val combinedLimit = if (ex.limitKmh > 0) ex.limitKmh else e.limitKmh
                    val combinedLevel = if (ex.roadLevel == RoadLevel.EXPRESSWAY || e.roadLevel == RoadLevel.EXPRESSWAY) {
                        RoadLevel.EXPRESSWAY
                    } else {
                        RoadLevel.SURFACE
                    }
                    val combinedDetail = when {
                        ex.techDetail.isBlank() -> e.techDetail
                        e.techDetail.isBlank() || ex.techDetail.contains(e.techDetail) -> ex.techDetail
                        else -> "${ex.techDetail} / ${e.techDetail}"
                    }
                    merged[dupIdx] = ex.copy(
                        limitKmh = combinedLimit,
                        techDetail = combinedDetail,
                        roadLevel = combinedLevel
                    )
                }
            }
            return merged
        }

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
                    val fullAddr = if (town.isNotBlank() && !addr.contains(town)) "$town$addr" else addr
                    out.add(
                        CameraPoint(
                            id = "cam_${i}_${"%.5f".format(lat)}_${"%.5f".format(lon)}",
                            lat = lat,
                            lon = lon,
                            limitKmh = limit,
                            directionText = dir,
                            address = fullAddr,
                            city = city,
                            kind = kind,
                            headingDeg = heading,
                            bidirectional = bidir,
                            roadLevel = classifyRoadLevel(fullAddr, limit)
                        )
                    )
                }
            }
            return out
        }

        /**
         * Accurately parses Taiwan police direction strings:
         * - "北向南" / "北往南" / "往南" / "南下" / "南向" -> 180° (traveling South)
         * - "南向北" / "南往北" / "往北" / "北上" / "北向" -> 0° (traveling North)
         * - "東向西" / "東往西" / "往西" / "西向" -> 270° (traveling West)
         * - "西向東" / "西往東" / "往東" / "東向" -> 90° (traveling East)
         * - "西南向東北" -> 45° (traveling Northeast)
         * - "雙向" / "南北雙向" / "東西雙向" / "多向" -> bidirectional = true
         */
        fun parseDirectionStatic(dir: String): Pair<Float?, Boolean> {
            val d = dir.trim()
            if (d.isEmpty() || d.contains("雙向") || d.contains("多向") || d.contains("雙") || d.contains("全向")) {
                val base = when {
                    d.contains("南北") || d.contains("北南") -> 0f
                    d.contains("東西") || d.contains("西東") -> 90f
                    else -> null
                }
                return base to true
            }

            // 1. Pattern "A向B" or "A往B" -> extract destination B!
            val fromToMatch = Regex("[東西南北]{1,2}[向往]([東西南北]{1,2})").find(d)
            if (fromToMatch != null) {
                val dest = fromToMatch.groupValues[1]
                val deg = compassToDeg(dest)
                if (deg != null) return deg to false
            }

            // 2. Pattern "往B" or "向B"
            val towardMatch = Regex("[往向]([東西南北]{1,2})").find(d)
            if (towardMatch != null) {
                val dest = towardMatch.groupValues[1]
                val deg = compassToDeg(dest)
                if (deg != null) return deg to false
            }

            // 3. Pattern "B向" / "B上" / "B下" / "B行" or standalone compass
            val singleMatch = Regex("(東北|東南|西南|西北|[東西南北])").find(d)
            if (singleMatch != null) {
                val deg = compassToDeg(singleMatch.groupValues[1])
                if (deg != null) return deg to false
            }

            return null to true
        }

        private fun compassToDeg(c: String): Float? = when (c) {
            "北" -> 0f
            "東北", "北東" -> 45f
            "東" -> 90f
            "東南", "南東" -> 135f
            "南" -> 180f
            "西南", "南西" -> 225f
            "西" -> 270f
            "西北", "北西" -> 315f
            else -> null
        }

        private fun isLonHeader(h: String): Boolean {
            val lower = h.lowercase()
            return lower.contains("經度") || lower.contains("經(e)") ||
                lower.contains("longitude") || lower == "lon" || lower == "lng" || lower == "x" || lower.contains("x座標")
        }

        private fun isLatHeader(h: String): Boolean {
            val lower = h.lowercase()
            return lower.contains("緯度") || lower.contains("緯(n)") ||
                lower.contains("latitude") || lower == "lat" || lower == "y" || lower.contains("y座標")
        }

        private fun extractDouble(raw: String): Double? {
            val s = raw.trim()
            s.toDoubleOrNull()?.let { return it }
            return Regex("-?\\d+(?:\\.\\d+)?").find(s)?.value?.toDoubleOrNull()
        }

        private fun normalizeCoord(latOrY: Double, lonOrX: Double): Pair<Double, Double>? {
            if (latOrY in 20.0..27.0 && lonOrX in 117.0..124.0) {
                return latOrY to lonOrX
            }
            // Sometimes lat/lon columns are swapped in dirty open-data CSVs
            if (lonOrX in 20.0..27.0 && latOrY in 117.0..124.0) {
                return lonOrX to latOrY
            }
            if (lonOrX in 100000.0..400000.0 && latOrY in 2400000.0..2900000.0) {
                val (lat, lon) = GeoMath.twd97ToWgs84(lonOrX, latOrY)
                if (lat in 20.0..27.0 && lon in 117.0..124.0) return lat to lon
            }
            return null
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
    }
}
