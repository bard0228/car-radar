package tw.bard.carradar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import tw.bard.carradar.R
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.data.CameraRepository
import tw.bard.carradar.data.CustomCameraStore
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.databinding.ActivityMapBinding
import tw.bard.carradar.service.RadarMonitorService

class MapActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: CameraRepository
    private lateinit var customStore: CustomCameraStore
    private var locOverlay: MyLocationNewOverlay? = null
    private var allCameras: List<CameraPoint> = emptyList()
    private var selectedCamera: CameraPoint? = null
    private val activeMarkers = ArrayList<Marker>()
    private var customMarkerIcon: BitmapDrawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.topBar)
        applySafeInsets(binding.pointSheet)

        prefs = Prefs(this)
        repo = CameraRepository(this)
        customStore = CustomCameraStore(this)
        customMarkerIcon = BitmapDrawable(resources, createCustomPinBitmap())

        binding.btnBack.setOnClickListener { finish() }

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(25.033964, 121.564468))
        }

        // Long press anywhere on map to add a custom camera at that exact spot
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                binding.pointSheet.visibility = View.GONE
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    showAddCustomDialog(p.latitude, p.longitude)
                }
                return true
            }
        })
        binding.mapView.overlays.add(0, eventsOverlay)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val myLoc = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
                setDirectionIcon(createBlueArrowBitmap())
                setPersonIcon(createBlueDotBitmap())
                enableMyLocation()
                enableFollowLocation()
            }
            locOverlay = myLoc
            binding.mapView.overlays.add(myLoc)
        }

        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                refreshVisibleMarkers()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                refreshVisibleMarkers()
                return false
            }
        })

        binding.btnAddPoint.setOnClickListener {
            val myGeo = locOverlay?.myLocation
            val center = binding.mapView.mapCenter
            val lat = myGeo?.latitude ?: center.latitude
            val lon = myGeo?.longitude ?: center.longitude
            showAddCustomDialog(lat, lon)
        }

        binding.btnMuteToggle.setOnClickListener {
            val cam = selectedCamera ?: return@setOnClickListener
            if (prefs.isCameraMuted(cam.id)) {
                prefs.unmuteCamera(cam.id)
            } else {
                prefs.muteCamera(cam.id)
            }
            updatePointSheet(cam)
            refreshVisibleMarkers()
        }

        binding.btnDeleteCustom.setOnClickListener {
            val cam = selectedCamera ?: return@setOnClickListener
            if (!cam.id.startsWith(CustomCameraStore.ID_PREFIX)) return@setOnClickListener
            customStore.delete(cam.id)
            prefs.unmuteCamera(cam.id)
            binding.pointSheet.visibility = View.GONE
            selectedCamera = null
            RadarMonitorService.reloadData(this)
            reloadCameras()
            Toast.makeText(this, "已刪除自訂點位", Toast.LENGTH_SHORT).show()
        }

        reloadCameras()
    }

    private fun reloadCameras() {
        lifecycleScope.launch {
            allCameras = repo.loadCameras()
            binding.tvMapInfo.text = "共 ${allCameras.size} 處 (長按地圖可新增)"
            refreshVisibleMarkers()
        }
    }

    private fun showAddCustomDialog(lat: Double, lon: Double) {
        val pad = (resources.displayMetrics.density * 18).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val rg = RadioGroup(this)
        val rbSpeed = RadioButton(this).apply { text = "測速照相"; isChecked = true }
        val rbRed = RadioButton(this).apply { text = "闖紅燈" }
        val rbTech = RadioButton(this).apply { text = "科技執法" }
        rg.addView(rbSpeed)
        rg.addView(rbRed)
        rg.addView(rbTech)
        container.addView(rg)

        val speeds = arrayOf(30, 40, 50, 60, 70, 80, 90, 100, 110)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MapActivity, android.R.layout.simple_spinner_dropdown_item, speeds.map { "速限 $it km/h" })
            setSelection(2) // default 50
        }
        container.addView(spinner)

        rg.setOnCheckedChangeListener { _, checkedId ->
            spinner.visibility = if (checkedId == rbSpeed.id) View.VISIBLE else View.GONE
        }

        val etNote = EditText(this).apply {
            hint = "地點備註（選填，例如：自訂路口）"
        }
        container.addView(etNote)

        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_CarRadar_Dialog)
            .setTitle("新增自訂點位")
            .setView(container)
            .setPositiveButton("新增") { _, _ ->
                val kind = when (rg.checkedRadioButtonId) {
                    rbRed.id -> CameraKind.RED_LIGHT
                    rbTech.id -> CameraKind.TECH
                    else -> CameraKind.SPEED
                }
                val limit = if (kind == CameraKind.SPEED) speeds[spinner.selectedItemPosition] else 0
                val note = etNote.text.toString().trim()
                val added = customStore.add(lat, lon, kind, limit, note)
                RadarMonitorService.reloadData(this)
                reloadCameras()
                selectedCamera = added
                updatePointSheet(added)
                Toast.makeText(this, "已新增自訂點位（更新政府資料時不會被覆蓋）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_glass_dialog)
        dialog.show()
    }

    private fun refreshVisibleMarkers() {
        if (allCameras.isEmpty()) return
        val box = binding.mapView.boundingBox
        val zoom = binding.mapView.zoomLevelDouble
        if (zoom < 10.5) {
            clearMarkers()
            binding.tvMapInfo.text = "放大地圖以顯示點位 (${allCameras.size})"
            return
        }

        clearMarkers()
        var count = 0
        for (cam in allCameras) {
            if (cam.lat in box.latSouth..box.latNorth && cam.lon in box.lonWest..box.lonEast) {
                val isCustom = cam.id.startsWith(CustomCameraStore.ID_PREFIX)
                val m = Marker(binding.mapView).apply {
                    position = GeoPoint(cam.lat, cam.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = if (isCustom) "[自訂] ${cam.displayLabel()}" else cam.displayLabel()
                    if (isCustom) {
                        icon = customMarkerIcon
                    }
                    alpha = if (prefs.isCameraMuted(cam.id)) 0.35f else 1.0f
                    setOnMarkerClickListener { _, _ ->
                        selectedCamera = cam
                        updatePointSheet(cam)
                        true
                    }
                }
                binding.mapView.overlays.add(m)
                activeMarkers.add(m)
                count++
                if (count >= 120) break
            }
        }
        binding.tvMapInfo.text = "視野 $count 處 (長按地圖可新增)"
        binding.mapView.invalidate()
    }

    private fun clearMarkers() {
        for (m in activeMarkers) {
            binding.mapView.overlays.remove(m)
        }
        activeMarkers.clear()
    }

    private fun updatePointSheet(cam: CameraPoint) {
        binding.pointSheet.visibility = View.VISIBLE
        val isCustom = cam.id.startsWith(CustomCameraStore.ID_PREFIX)
        val muted = prefs.isCameraMuted(cam.id)
        val prefix = if (isCustom) "[自訂] " else ""
        binding.tvPointTitle.text = "$prefix${cam.displayLabel()} · ${cam.address.ifBlank { "執法點位" }}"
        val detail = buildString {
            if (cam.limitKmh > 0) append("速限 ${cam.limitKmh} km/h")
            if (cam.techDetail.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(cam.techDetail)
            }
            if (muted) {
                if (isNotEmpty()) append(" · ")
                append("已靜音")
            }
        }
        binding.tvPointDetail.text = detail.ifBlank { "測速/執法點位" }
        binding.btnMuteToggle.text = if (muted) "恢復提醒" else "這個點不用報"
        binding.btnDeleteCustom.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun createCustomPinBitmap(): Bitmap {
        val size = (resources.displayMetrics.density * 28).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5B8270")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(c, c, size * 0.44f, white)
        canvas.drawCircle(c, c, size * 0.34f, orange)
        return bmp
    }

    private fun createBlueArrowBitmap(): Bitmap {
        val size = (resources.displayMetrics.density * 34).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, size * 0.08f)
            lineTo(size * 0.86f, size * 0.86f)
            lineTo(cx, size * 0.68f)
            lineTo(size * 0.14f, size * 0.86f)
            close()
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.10f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)
        return bmp
    }

    private fun createBlueDotBitmap(): Bitmap {
        val size = (resources.displayMetrics.density * 24).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(c, c, size * 0.44f, white)
        canvas.drawCircle(c, c, size * 0.30f, blue)
        return bmp
    }
}
