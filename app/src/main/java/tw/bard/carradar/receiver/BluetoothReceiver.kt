package tw.bard.carradar.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.service.RadarMonitorService

class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = Prefs(context)
        if (!prefs.btAutoEnabled) return

        val targets = prefs.btDeviceAddresses
        if (targets.isEmpty()) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val addr = try {
            device?.address
        } catch (_: SecurityException) {
            null
        } ?: return

        if (!targets.contains(addr)) return

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                RadarMonitorService.startByBluetooth(context)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                RadarMonitorService.notifyBluetoothDisconnected(context)
            }
        }
    }
}
