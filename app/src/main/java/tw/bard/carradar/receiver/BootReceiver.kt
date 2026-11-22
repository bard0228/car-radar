package tw.bard.carradar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.service.RadarMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = Prefs(context)
            if (prefs.monitoringEnabled && RadarMonitorService.hasLocationPermission(context)) {
                RadarMonitorService.startManual(context)
            }
        }
    }
}
