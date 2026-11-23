package tw.bard.carradar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.bard.carradar.data.AudioFocusMode
import tw.bard.carradar.data.CameraRepository
import tw.bard.carradar.data.OverlayDisplayMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.databinding.ActivitySettingsBinding
import tw.bard.carradar.service.RadarMonitorService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: CameraRepository

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openBluetoothPicker()
        else Toast.makeText(this, "需要鄰近裝置權限才能讀取已配對藍牙", Toast.LENGTH_SHORT).show()
    }

    private val bgLocLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(this, if (granted) "已取得背景位置權限" else "請在系統設定選擇一律允許", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.rootContainer)

        prefs = Prefs(this)
        repo = CameraRepository(this)

        binding.btnBack.setOnClickListener { finish() }

        // Bluetooth
        binding.switchBtAuto.isChecked = prefs.btAutoEnabled
        binding.switchBtAuto.setOnCheckedChangeListener { _, checked ->
            prefs.btAutoEnabled = checked
        }
        binding.btnPickBt.setOnClickListener { pickBluetoothDevices() }
        updateBtSummary()

        // Distance Sliders
        binding.sliderLow.value = prefs.lowSpeedDistM.toFloat().coerceIn(150f, 1200f)
        binding.sliderMid.value = prefs.midSpeedDistM.toFloat().coerceIn(150f, 1200f)
        binding.sliderHigh.value = prefs.highSpeedDistM.toFloat().coerceIn(150f, 1200f)
        binding.sliderTech.value = prefs.techDistM.toFloat().coerceIn(150f, 800f)
        updateDistLabels()

        binding.sliderLow.addOnChangeListener { _, v, _ ->
            prefs.lowSpeedDistM = v.toInt()
            updateDistLabels()
        }
        binding.sliderMid.addOnChangeListener { _, v, _ ->
            prefs.midSpeedDistM = v.toInt()
            updateDistLabels()
        }
        binding.sliderHigh.addOnChangeListener { _, v, _ ->
            prefs.highSpeedDistM = v.toInt()
            updateDistLabels()
        }
        binding.sliderTech.addOnChangeListener { _, v, _ ->
            prefs.techDistM = v.toInt()
            updateDistLabels()
        }

        // Audio focus & volume
        if (prefs.audioFocusMode == AudioFocusMode.DUCK) binding.rbDuck.isChecked = true
        else binding.rbMix.isChecked = true
        binding.rgAudioFocus.setOnCheckedChangeListener { _, checkedId ->
            prefs.audioFocusMode = if (checkedId == binding.rbMix.id) AudioFocusMode.MIX else AudioFocusMode.DUCK
        }
        binding.sliderVolume.value = prefs.voiceVolumePct.toFloat().coerceIn(10f, 100f)
        binding.tvVolume.text = "語音播報音量：${prefs.voiceVolumePct}%"
        binding.sliderVolume.addOnChangeListener { _, v, _ ->
            prefs.voiceVolumePct = v.toInt()
            binding.tvVolume.text = "語音播報音量：${prefs.voiceVolumePct}%"
        }
        val testPlayer = tw.bard.carradar.audio.RadarAudioPlayer(
            this,
            prefs,
            tw.bard.carradar.data.VoicePackManager(this)
        )
        binding.btnTestVolume.setOnClickListener {
            testPlayer.playPhrases(listOf("80"))
        }

        // Overlay
        binding.switchOverlay.isChecked = prefs.overlayEnabled
        binding.switchOverlay.setOnCheckedChangeListener { _, checked ->
            prefs.overlayEnabled = checked
        }
        if (prefs.overlayMode == OverlayDisplayMode.ALWAYS) binding.rbAlways.isChecked = true
        else binding.rbApproachOnly.isChecked = true
        binding.rgOverlayMode.setOnCheckedChangeListener { _, checkedId ->
            prefs.overlayMode = if (checkedId == binding.rbAlways.id) OverlayDisplayMode.ALWAYS else OverlayDisplayMode.APPROACH_ONLY
        }
        binding.btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "懸浮視窗權限已開啟", Toast.LENGTH_SHORT).show()
            }
        }

        // Background location & battery optimization
        updatePermissionLabels()
        binding.btnBgLocation.setOnClickListener {
            if (!RadarMonitorService.hasLocationPermission(this)) {
                Toast.makeText(this, "請先在權限頁開啟位置並設為「一律允許」", Toast.LENGTH_SHORT).show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        binding.btnBatteryOpt.setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            } else {
                Toast.makeText(this, "已關閉電池最佳化", Toast.LENGTH_SHORT).show()
            }
        }

        // Online data sync
        updateSyncLabel()
        binding.btnUpdateData.setOnClickListener {
            binding.btnUpdateData.isEnabled = false
            binding.btnUpdateData.text = "更新中..."
            lifecycleScope.launch {
                val res = repo.refreshFromOpenData(prefs)
                binding.btnUpdateData.isEnabled = true
                binding.btnUpdateData.text = "線上更新政府測速點資料"
                res.onSuccess { count ->
                    RadarMonitorService.reloadData(this@SettingsActivity)
                    updateSyncLabel()
                    Toast.makeText(this@SettingsActivity, "已更新 $count 筆測速資料", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this@SettingsActivity, "更新失敗：${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnCrashLogs.setOnClickListener {
            startActivity(Intent(this, CrashLogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionLabels()
    }

    private fun updatePermissionLabels() {
        val hasBg = RadarMonitorService.hasBackgroundLocationPermission(this)
        binding.btnBgLocation.text = if (hasBg) "背景位置：已一律允許" else "背景位置：尚未一律允許 (點此開啟)"
        val pm = getSystemService(PowerManager::class.java)
        val noOpt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBatteryOpt.text = if (noOpt) "電池最佳化：已關閉" else "關閉電池最佳化 (防背景中斷)"
    }

    private fun updateDistLabels() {
        binding.tvLowDist.text = "低速 (<60 km/h)：${prefs.lowSpeedDistM} m"
        binding.tvMidDist.text = "中速 (60~89 km/h)：${prefs.midSpeedDistM} m"
        binding.tvHighDist.text = "高速 (>=90 km/h)：${prefs.highSpeedDistM} m"
        binding.tvTechDist.text = "路口科技執法 / 闖紅燈：${prefs.techDistM} m"
    }

    private fun updateBtSummary() {
        val count = prefs.btDeviceAddresses.size
        binding.tvBtDevices.text = if (count == 0) {
            "尚未選擇車機藍牙"
        } else {
            "已綁定 $count 台車機藍牙 (${prefs.btDeviceAddresses.joinToString(", ")})"
        }
    }

    private fun updateSyncLabel() {
        val ms = prefs.lastDataSyncMs
        if (ms <= 0L) {
            binding.tvSyncStatus.text = "使用內建最新警政署與各縣市開放資料"
        } else {
            val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Taipei"))
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            binding.tvSyncStatus.text = "最後線上更新：${zdt.format(fmt)}"
        }
    }

    private fun pickBluetoothDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        openBluetoothPicker()
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothPicker() {
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter
        if (adapter == null) {
            Toast.makeText(this, "此裝置不支援藍牙", Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = adapter.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            Toast.makeText(this, "請先在系統設定配對車機藍牙", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = prefs.btDeviceAddresses.toMutableSet()
        val labels = bonded.map { "${it.name ?: "未知裝置"} (${it.address})" }.toTypedArray()
        val checked = BooleanArray(bonded.size) { idx -> selected.contains(bonded[idx].address) }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, tw.bard.carradar.R.style.Theme_CarRadar_Dialog)
            .setTitle("選擇車機藍牙裝置")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val addr = bonded[which].address
                if (isChecked) selected.add(addr) else selected.remove(addr)
            }
            .setPositiveButton("儲存") { _, _ ->
                prefs.btDeviceAddresses = selected
                updateBtSummary()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(tw.bard.carradar.R.drawable.bg_glass_dialog)
        dialog.show()
    }
}
