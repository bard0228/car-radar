package tw.bard.carradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tw.bard.carradar.core.MileageRecord
import java.io.File

class MileageStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "mileage_history.json")
    private val lock = Any()

    fun listRecords(): List<MileageRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<MileageRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    MileageRecord(
                        id = o.getString("id"),
                        date = o.getString("date"),
                        startTime = o.getString("startTime"),
                        endTime = o.getString("endTime"),
                        distanceKm = o.getDouble("distanceKm"),
                        startEpochMs = o.optLong("startEpochMs", 0L),
                        endEpochMs = o.optLong("endEpochMs", 0L)
                    )
                )
            }
            out.sortedByDescending { it.startEpochMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun totalKm(): Double = listRecords().sumOf { it.distanceKm }

    /**
     * Synchronous write so data is guaranteed flushed before Process.killProcess().
     */
    fun addRecordSync(record: MileageRecord) = synchronized(lock) {
        val current = listRecords().toMutableList()
        if (current.any { it.id == record.id }) return@synchronized
        current.add(0, record)
        writeSync(current)
    }

    fun deleteRecord(id: String) = synchronized(lock) {
        val filtered = listRecords().filterNot { it.id == id }
        writeSync(filtered)
    }

    fun clearAll() = synchronized(lock) {
        if (file.exists()) file.delete()
    }

    private fun writeSync(records: List<MileageRecord>) {
        val arr = JSONArray()
        for (r in records) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("date", r.date)
            o.put("startTime", r.startTime)
            o.put("endTime", r.endTime)
            o.put("distanceKm", r.distanceKm)
            o.put("startEpochMs", r.startEpochMs)
            o.put("endEpochMs", r.endEpochMs)
            arr.put(o)
        }
        file.writeText(arr.toString(), Charsets.UTF_8)
    }
}
