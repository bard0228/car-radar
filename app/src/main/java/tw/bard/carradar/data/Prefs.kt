package tw.bard.carradar.data

import android.content.Context
import android.content.SharedPreferences
import tw.bard.carradar.core.AlertDistancesConfig

enum class AudioFocusMode {
    DUCK, // 壓低背景音樂
    MIX   // 純混音不壓低
}

enum class OverlayDisplayMode {
    APPROACH_ONLY, // 僅接近照相機時彈出
    ALWAYS         // 監控中全程常駐
}

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("car_radar_prefs", Context.MODE_PRIVATE)

    var monitoringEnabled: Boolean
        get() = sp.getBoolean("monitoring_enabled", false)
        set(v) { sp.edit().putBoolean("monitoring_enabled", v).commit() }

    // 車機藍牙綁定 (Address -> Name)
    var btDeviceAddresses: Set<String>
        get() = sp.getStringSet("bt_addresses", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("bt_addresses", v).apply() }

    var btAutoEnabled: Boolean
        get() = sp.getBoolean("bt_auto_enabled", true)
        set(v) { sp.edit().putBoolean("bt_auto_enabled", v).apply() }

    // 預警距離滑桿
    var lowSpeedDistM: Int
        get() = sp.getInt("dist_low_m", 300)
        set(v) { sp.edit().putInt("dist_low_m", v).apply() }

    var midSpeedDistM: Int
        get() = sp.getInt("dist_mid_m", 500)
        set(v) { sp.edit().putInt("dist_mid_m", v).apply() }

    var highSpeedDistM: Int
        get() = sp.getInt("dist_high_m", 500)
        set(v) { sp.edit().putInt("dist_high_m", v).apply() }

    var techDistM: Int
        get() = sp.getInt("dist_tech_m", 250)
        set(v) { sp.edit().putInt("dist_tech_m", v).apply() }

    fun alertDistancesConfig(): AlertDistancesConfig = AlertDistancesConfig(
        lowSpeedDistM = lowSpeedDistM,
        midSpeedDistM = midSpeedDistM,
        highSpeedDistM = highSpeedDistM,
        techDistM = techDistM
    )

    // 音訊焦點模式
    var audioFocusMode: AudioFocusMode
        get() = when (sp.getString("audio_focus_mode", "DUCK")) {
            "MIX" -> AudioFocusMode.MIX
            else -> AudioFocusMode.DUCK
        }
        set(v) { sp.edit().putString("audio_focus_mode", v.name).apply() }

    // 語音包選擇
    var activeVoicePackId: String
        get() = sp.getString("active_voice_pack", "default_male") ?: "default_male"
        set(v) { sp.edit().putString("active_voice_pack", v).apply() }

    // 懸浮視窗
    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", true)
        set(v) { sp.edit().putBoolean("overlay_enabled", v).apply() }

    var overlayMode: OverlayDisplayMode
        get() = when (sp.getString("overlay_mode", "APPROACH_ONLY")) {
            "ALWAYS" -> OverlayDisplayMode.ALWAYS
            else -> OverlayDisplayMode.APPROACH_ONLY
        }
        set(v) { sp.edit().putString("overlay_mode", v.name).apply() }

    var overlayX: Int
        get() = sp.getInt("overlay_x", 40)
        set(v) { sp.edit().putInt("overlay_x", v).apply() }

    var overlayY: Int
        get() = sp.getInt("overlay_y", 360)
        set(v) { sp.edit().putInt("overlay_y", v).apply() }

    var overlaySide: String
        get() = sp.getString("overlay_side", "RIGHT") ?: "RIGHT"
        set(v) { sp.edit().putString("overlay_side", v).apply() }

    // 單點靜音清單
    var mutedCameraIds: Set<String>
        get() = sp.getStringSet("muted_cameras", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("muted_cameras", v).apply() }

    fun muteCamera(id: String) {
        val next = mutedCameraIds.toMutableSet()
        next.add(id)
        mutedCameraIds = next
    }

    fun unmuteCamera(id: String) {
        val next = mutedCameraIds.toMutableSet()
        next.remove(id)
        mutedCameraIds = next
    }

    fun isCameraMuted(id: String): Boolean = mutedCameraIds.contains(id)

    // 最後一次更新時間
    var lastDataSyncMs: Long
        get() = sp.getLong("last_data_sync_ms", 0L)
        set(v) { sp.edit().putLong("last_data_sync_ms", v).apply() }
}
