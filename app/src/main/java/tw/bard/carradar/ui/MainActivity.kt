package tw.bard.carradar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.bard.carradar.R
import tw.bard.carradar.data.MileageStore
import tw.bard.carradar.databinding.ActivityMainBinding
import tw.bard.carradar.service.MonitorStateBus
import tw.bard.carradar.service.RadarMonitorService

class MainActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mileageStore: MileageStore

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse || RadarMonitorService.hasLocationPermission(this)) {
            RadarMonitorService.startManual(this)
        } else {
            android.widget.Toast.makeText(this, "需要位置權限才能開始測速監控", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.rootContainer)

        mileageStore = MileageStore(this)

        attachSpringPress(binding.btnToggleMonitor)
        attachSpringPress(binding.cardMap)
        attachSpringPress(binding.cardVoice)
        attachSpringPress(binding.cardMileage)
        attachSpringPress(binding.cardSettings)

        binding.btnToggleMonitor.setOnClickListener {
            if (RadarMonitorService.isRunning) {
                RadarMonitorService.stopManual(this)
            } else {
                ensureLocationAndStart()
            }
        }

        binding.cardMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.cardVoice.setOnClickListener {
            startActivity(Intent(this, VoiceStudioActivity::class.java))
        }
        binding.cardMileage.setOnClickListener {
            startActivity(Intent(this, MileageActivity::class.java))
        }
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            MonitorStateBus.state.collect { st ->
                binding.tvSpeed.text = st.speedKmh.toString()
                binding.tvSessionKm.text = "${"%.1f".format(st.sessionKm)} km"
                binding.tvTotalKm.text = "${"%.1f".format(mileageStore.totalKm() + st.sessionKm)} km"

                if (st.isRunning) {
                    binding.tvStatusPill.text = if (st.isBtMode) {
                        getString(R.string.status_bt_active)
                    } else {
                        getString(R.string.status_monitoring)
                    }
                    binding.tvStatusPill.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_teal))
                    binding.btnToggleMonitor.text = getString(R.string.btn_stop)
                    binding.btnToggleMonitor.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.alert_red))
                } else {
                    binding.tvStatusPill.text = getString(R.string.status_idle)
                    binding.tvStatusPill.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    binding.btnToggleMonitor.text = getString(R.string.btn_start)
                    binding.btnToggleMonitor.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_orange))
                }

                val hud = st.activeHud
                val zone = st.activeZone
                when {
                    hud != null -> {
                        binding.tvAheadTitle.text = hud.camera.address.ifBlank { hud.camera.displayLabel() }
                        binding.tvAheadSub.text = "距離約 ${hud.distanceM} 公尺"
                        binding.tvAheadBadge.text = hud.camera.displayLabel()
                    }
                    zone != null -> {
                        binding.tvAheadTitle.text = zone.name
                        binding.tvAheadSub.text = "區間測速中"
                        binding.tvAheadBadge.text = "區間 ${zone.limitKmh}"
                    }
                    else -> {
                        binding.tvAheadTitle.text = "前方無執法點位"
                        binding.tvAheadSub.text = if (st.lastAlertLabel.isNotBlank()) "上次通報：${st.lastAlertLabel}" else "極簡語音待命中"
                        binding.tvAheadBadge.text = "--"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val currentSession = MonitorStateBus.state.value.sessionKm
        binding.tvTotalKm.text = "${"%.1f".format(mileageStore.totalKm() + currentSession)} km"
        RadarMonitorService.restoreOverlay(this)
        checkUnreadCrashLog()
    }

    private fun checkUnreadCrashLog() {
        val crashStore = tw.bard.carradar.data.CrashLogStore(this)
        if (!crashStore.hasUnread()) return
        crashStore.markRead()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_CarRadar_Dialog)
            .setTitle("偵測到異常閃退")
            .setMessage("已記錄上次閃退的完整錯誤訊息，您可以直接複製或查看詳情。")
            .setPositiveButton("複製錯誤") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("CarRadarCrashLog", crashStore.readAll()))
                android.widget.Toast.makeText(this, "已複製錯誤記錄", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("查看詳情") { _, _ ->
                startActivity(Intent(this, CrashLogActivity::class.java))
            }
            .setNegativeButton("關閉", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_glass_dialog)
        dialog.show()
    }

    private fun ensureLocationAndStart() {
        val needed = mutableListOf<String>()
        if (!RadarMonitorService.hasLocationPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            RadarMonitorService.startManual(this)
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }
}
