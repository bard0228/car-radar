package tw.bard.carradar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.data.CameraRepository
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.databinding.ActivityMapBinding

class MapActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: CameraRepository
    private var allCameras: List<CameraPoint> = emptyList()
    private var selectedCamera: CameraPoint? = null
    private val activeMarkers = ArrayList<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.topBar)
        applySafeInsets(binding.pointSheet)

        prefs = Prefs(this)
        repo = CameraRepository(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(25.033964, 121.564468))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
            locOverlay.setDirectionIcon(createBlueArrowBitmap())
            locOverlay.setPersonIcon(createBlueDotBitmap())
            locOverlay.enableMyLocation()
            locOverlay.enableFollowLocation()
            binding.mapView.overlays.add(locOverlay)
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

        lifecycleScope.launch {
            allCameras = repo.loadCameras()
            binding.tvMapInfo.text = "共 ${allCameras.size} 處執法點位"
            refreshVisibleMarkers()
        }
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
                val m = Marker(binding.mapView).apply {
                    position = GeoPoint(cam.lat, cam.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = cam.displayLabel()
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
        binding.tvMapInfo.text = "目前視野 $count 處點位"
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
        val muted = prefs.isCameraMuted(cam.id)
        binding.tvPointTitle.text = "${cam.displayLabel()} · ${cam.address.ifBlank { "執法點位" }}"
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
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun createBlueArrowBitmap(): android.graphics.Bitmap {
        val size = (resources.displayMetrics.density * 34).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = size / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, size * 0.08f)
            lineTo(size * 0.86f, size * 0.86f)
            lineTo(cx, size * 0.68f)
            lineTo(size * 0.14f, size * 0.86f)
            close()
        }
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size * 0.10f
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#2563EB")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)
        return bmp
    }

    private fun createBlueDotBitmap(): android.graphics.Bitmap {
        val size = (resources.displayMetrics.density * 24).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val c = size / 2f
        val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val blue = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#2563EB")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(c, c, size * 0.44f, white)
        canvas.drawCircle(c, c, size * 0.30f, blue)
        return bmp
    }
}
