package tw.bard.carradar.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tw.bard.carradar.R
import tw.bard.carradar.audio.RadarAudioPlayer
import tw.bard.carradar.core.CameraAlerter
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.core.IntervalAlertEvent
import tw.bard.carradar.core.IntervalTracker
import tw.bard.carradar.core.IntervalZone
import tw.bard.carradar.core.MileageCalculator
import tw.bard.carradar.core.RadarAlertEvent
import tw.bard.carradar.data.CameraRepository
import tw.bard.carradar.data.MileageStore
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.data.VoicePackManager
import tw.bard.carradar.overlay.OverlayController
import tw.bard.carradar.ui.MainActivity

class RadarMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var repo: CameraRepository
    private lateinit var mileageStore: MileageStore
    private lateinit var audioPlayer: RadarAudioPlayer
    private lateinit var overlay: OverlayController
    private lateinit var fusedClient: FusedLocationProviderClient

    private val alerter = CameraAlerter()
    private val intervalTracker = IntervalTracker()
    private val mileageCalc = MileageCalculator()

    private var cameras: List<CameraPoint> = emptyList()
    private var zones: List<IntervalZone> = emptyList()
    private var isBtMode: Boolean = false
    private var pendingDisconnectKill: Runnable? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleLocation(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = CameraRepository(this)
        mileageStore = MileageStore(this)
        val voicePacks = VoicePackManager(this)
        audioPlayer = RadarAudioPlayer(this, prefs, voicePacks)
        overlay = OverlayController(this, prefs)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        alerter.isMuted = { cam -> prefs.isCameraMuted(cam.id) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MANUAL, ACTION_START_BT -> {
                cancelPendingDisconnectKill()
                isBtMode = (intent.action == ACTION_START_BT)
                if (!hasLocationPermission(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIF_ID,
                        buildNotification("測速監控啟動中"),
                        fgType
                    )
                } catch (e: Exception) {
                    tw.bard.carradar.data.CrashLogStore(this).recordThrowable("FGS_START", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMonitoringSession()
            }
            ACTION_BT_DISCONNECT -> {
                scheduleBtDisconnectKill()
            }
            ACTION_STOP_MANUAL -> {
                cancelPendingDisconnectKill()
                stopAndSaveSession(killProcess = false)
            }
            ACTION_RELOAD_DATA -> {
                scope.launch {
                    cameras = repo.loadCameras()
                    zones = repo.loadZones()
                    MonitorStateBus.update {
                        it.copy(cameraCount = cameras.size, zoneCount = zones.size)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoringSession() {
        isRunning = true
        prefs.monitoringEnabled = true
        if (mileageCalc.sessionStartMs == 0L) {
            mileageCalc.startSession()
        }
        alerter.distConfig = prefs.alertDistancesConfig()
        MonitorStateBus.update {
            it.copy(isRunning = true, isBtMode = isBtMode)
        }

        scope.launch {
            if (cameras.isEmpty()) {
                cameras = repo.loadCameras()
            }
            if (zones.isEmpty()) {
                zones = repo.loadZones()
            }
            MonitorStateBus.update {
                it.copy(cameraCount = cameras.size, zoneCount = zones.size)
            }
        }

        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!hasLocationPermission(this)) return
        runCatching {
            fusedClient.removeLocationUpdates(locationCallback)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }
    }

    private fun handleLocation(loc: Location) {
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        val heading = if (loc.hasBearing() && speedKmh >= 5f) loc.bearing else null

        alerter.distConfig = prefs.alertDistancesConfig()
        val sessionKm = mileageCalc.onLocation(loc.latitude, loc.longitude, speedKmh, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())

        val (camEvent, hud) = alerter.update(cameras, loc.latitude, loc.longitude, speedKmh, heading)
        val zoneEvent = intervalTracker.update(zones, loc.latitude, loc.longitude, speedKmh)

        var lastLabel = MonitorStateBus.state.value.lastAlertLabel

        when (camEvent) {
            is RadarAlertEvent.Approach -> {
                audioPlayer.playPhrases(camEvent.phraseKeys)
                lastLabel = camEvent.camera.displayLabel()
            }
            is RadarAlertEvent.Passed -> {
                audioPlayer.playPhrases(camEvent.phraseKeys)
                lastLabel = "通過"
            }
            null -> Unit
        }

        when (zoneEvent) {
            is IntervalAlertEvent.Entered -> {
                audioPlayer.playPhrases(zoneEvent.phraseKeys)
                lastLabel = "區間 ${zoneEvent.zone.limitKmh}"
            }
            is IntervalAlertEvent.Exited -> {
                audioPlayer.playPhrases(zoneEvent.phraseKeys)
                lastLabel = "通過"
            }
            null -> Unit
        }

        val intSpeed = speedKmh.toInt().coerceAtLeast(0)
        overlay.update(intSpeed, hud)

        MonitorStateBus.update {
            it.copy(
                isRunning = true,
                isBtMode = isBtMode,
                speedKmh = intSpeed,
                sessionKm = sessionKm,
                activeHud = hud,
                activeZone = intervalTracker.activeZoneInfo(),
                lastAlertLabel = lastLabel
            )
        }

        val notifText = if (hud != null) {
            "${intSpeed} km/h · 前方 ${hud.camera.displayLabel()} (${hud.distanceM}m)"
        } else {
            "${intSpeed} km/h · 本次 ${"%.1f".format(sessionKm)} km"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(notifText))
    }

    private fun scheduleBtDisconnectKill() {
        cancelPendingDisconnectKill()
        val task = Runnable {
            stopAndSaveSession(killProcess = true)
        }
        pendingDisconnectKill = task
        mainHandler.postDelayed(task, BT_DISCONNECT_DEBOUNCE_MS)
    }

    private fun cancelPendingDisconnectKill() {
        pendingDisconnectKill?.let { mainHandler.removeCallbacks(it) }
        pendingDisconnectKill = null
    }

    private fun stopAndSaveSession(killProcess: Boolean) {
        isRunning = false
        prefs.monitoringEnabled = false
        fusedClient.removeLocationUpdates(locationCallback)
        overlay.hide()
        audioPlayer.release()

        // Synchronous disk persistence before process termination
        mileageCalc.buildRecord()?.let { record ->
            mileageStore.addRecordSync(record)
        }
        mileageCalc.startSession(0L)

        MonitorStateBus.update {
            MonitorUiState(
                isRunning = false,
                isBtMode = false,
                cameraCount = cameras.size,
                zoneCount = zones.size
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (killProcess) {
            sendBroadcast(Intent(MonitorStateBus.ACTION_KILL_TASK).setPackage(packageName))
            mainHandler.postDelayed({
                Process.killProcess(Process.myPid())
            }, 180L)
        }
    }

    override fun onDestroy() {
        cancelPendingDisconnectKill()
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        overlay.hide()
        audioPlayer.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RadarMonitorService::class.java).setAction(ACTION_STOP_MANUAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "car_radar_monitor"
        private const val NOTIF_ID = 1001
        private const val BT_DISCONNECT_DEBOUNCE_MS = 3000L

        const val ACTION_START_MANUAL = "tw.bard.carradar.ACTION_START_MANUAL"
        const val ACTION_START_BT = "tw.bard.carradar.ACTION_START_BT"
        const val ACTION_BT_DISCONNECT = "tw.bard.carradar.ACTION_BT_DISCONNECT"
        const val ACTION_STOP_MANUAL = "tw.bard.carradar.ACTION_STOP_MANUAL"
        const val ACTION_RELOAD_DATA = "tw.bard.carradar.ACTION_RELOAD_DATA"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun hasLocationPermission(context: Context): Boolean {
            val fine = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        fun startManual(context: Context) {
            val i = Intent(context, RadarMonitorService::class.java).setAction(ACTION_START_MANUAL)
            ContextCompat.startForegroundService(context, i)
        }

        fun stopManual(context: Context) {
            if (!isRunning) return
            val i = Intent(context, RadarMonitorService::class.java).setAction(ACTION_STOP_MANUAL)
            context.startService(i)
        }

        fun startByBluetooth(context: Context) {
            val i = Intent(context, RadarMonitorService::class.java).setAction(ACTION_START_BT)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun notifyBluetoothDisconnected(context: Context) {
            if (!isRunning) return
            val i = Intent(context, RadarMonitorService::class.java).setAction(ACTION_BT_DISCONNECT)
            runCatching { context.startService(i) }
        }

        fun reloadData(context: Context) {
            if (!isRunning) return
            val i = Intent(context, RadarMonitorService::class.java).setAction(ACTION_RELOAD_DATA)
            runCatching { context.startService(i) }
        }
    }
}
