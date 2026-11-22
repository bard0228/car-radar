package tw.bard.carradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class PhraseSlot(
    val key: String,
    val label: String
)

data class VoicePack(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean
)

sealed class VoiceClipSource {
    data class Asset(val assetPath: String) : VoiceClipSource()
    data class LocalFile(val file: File) : VoiceClipSource()
}

class VoicePackManager(private val context: Context) {

    private val packsRoot = File(context.applicationContext.filesDir, "voice_packs").apply { mkdirs() }
    private val metaFile = File(packsRoot, "packs.json")

    fun listPacks(): List<VoicePack> {
        val list = mutableListOf(VoicePack(DEFAULT_PACK_ID, "預設台灣男聲", true))
        if (metaFile.exists()) {
            runCatching {
                val arr = JSONArray(metaFile.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(VoicePack(o.getString("id"), o.getString("name"), false))
                }
            }
        }
        return list
    }

    fun createPack(name: String): VoicePack {
        val id = "pack_${System.currentTimeMillis()}"
        File(packsRoot, id).mkdirs()
        val custom = listPacks().filterNot { it.isBuiltIn }.toMutableList()
        val pack = VoicePack(id, name.ifBlank { "自訂語音" }, false)
        custom.add(pack)
        saveCustomPacks(custom)
        return pack
    }

    fun deletePack(packId: String) {
        if (packId == DEFAULT_PACK_ID) return
        File(packsRoot, packId).deleteRecursively()
        val remaining = listPacks().filterNot { it.isBuiltIn || it.id == packId }
        saveCustomPacks(remaining)
    }

    fun hasCustomClip(packId: String, key: String): Boolean {
        if (packId == DEFAULT_PACK_ID) return false
        return findCustomFile(packId, key) != null
    }

    fun targetRecordFile(packId: String, key: String): File {
        val dir = File(packsRoot, packId).apply { mkdirs() }
        removeExistingForKey(dir, key)
        return File(dir, "$key.m4a")
    }

    fun importClip(packId: String, key: String, ext: String, input: InputStream): File {
        val dir = File(packsRoot, packId).apply { mkdirs() }
        removeExistingForKey(dir, key)
        val safeExt = ext.lowercase().takeIf { it in listOf("m4a", "mp3", "wav", "ogg", "aac") } ?: "m4a"
        val out = File(dir, "$key.$safeExt")
        out.outputStream().use { os -> input.copyTo(os) }
        return out
    }

    fun clearClip(packId: String, key: String) {
        val dir = File(packsRoot, packId)
        if (dir.exists()) removeExistingForKey(dir, key)
    }

    fun resolveClip(packId: String, key: String): VoiceClipSource {
        if (packId != DEFAULT_PACK_ID) {
            val custom = findCustomFile(packId, key)
            if (custom != null && custom.exists() && custom.length() > 0) {
                return VoiceClipSource.LocalFile(custom)
            }
        }
        return VoiceClipSource.Asset("voice/default_male/$key.m4a")
    }

    private fun findCustomFile(packId: String, key: String): File? {
        val dir = File(packsRoot, packId)
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.nameWithoutExtension == key && it.length() > 0 }
    }

    private fun removeExistingForKey(dir: File, key: String) {
        dir.listFiles()?.filter { it.nameWithoutExtension == key }?.forEach { it.delete() }
    }

    private fun saveCustomPacks(packs: List<VoicePack>) {
        val arr = JSONArray()
        for (p in packs) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
            })
        }
        metaFile.writeText(arr.toString(), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_PACK_ID = "default_male"

        val SLOTS = listOf(
            PhraseSlot("30", "速限 30"),
            PhraseSlot("40", "速限 40"),
            PhraseSlot("50", "速限 50"),
            PhraseSlot("60", "速限 60"),
            PhraseSlot("70", "速限 70"),
            PhraseSlot("80", "速限 80"),
            PhraseSlot("90", "速限 90"),
            PhraseSlot("100", "速限 100"),
            PhraseSlot("110", "速限 110"),
            PhraseSlot("tech", "科技執法"),
            PhraseSlot("red_light", "闖紅燈"),
            PhraseSlot("interval", "區間"),
            PhraseSlot("pass", "通過")
        )
    }
}
