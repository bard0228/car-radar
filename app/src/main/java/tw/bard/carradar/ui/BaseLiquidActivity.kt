package tw.bard.carradar.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tw.bard.carradar.service.MonitorStateBus

abstract class BaseLiquidActivity : AppCompatActivity() {

    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MonitorStateBus.ACTION_KILL_TASK) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(MonitorStateBus.ACTION_KILL_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(killReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(killReceiver) }
        super.onDestroy()
    }

    protected fun applySafeInsets(root: View) {
        val initLeft = root.paddingLeft
        val initTop = root.paddingTop
        val initRight = root.paddingRight
        val initBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                initLeft + bars.left,
                initTop + bars.top,
                initRight + bars.right,
                initBottom + bars.bottom
            )
            insets
        }
        upgradeOpticalGlassTree(root)
    }

    protected fun upgradeOpticalGlassTree(view: View) {
        val density = resources.displayMetrics.density
        val tag = view.tag as? String
        when (tag) {
            "lg_card" -> {
                view.background = OpticalGlassDrawable(26f * density, density, isPill = false)
                view.elevation = 6f * density
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    view.outlineAmbientShadowColor = 0x261C2A24
                    view.outlineSpotShadowColor = 0x2E1C2A24
                }
            }
            "lg_pill" -> {
                view.background = OpticalGlassDrawable(999f, density, isPill = true)
                view.elevation = 4f * density
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    view.outlineAmbientShadowColor = 0x201C2A24
                    view.outlineSpotShadowColor = 0x261C2A24
                }
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                upgradeOpticalGlassTree(view.getChildAt(i))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun attachSpringPress(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }
            }
            false
        }
    }
}
