package tw.bard.carradar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import tw.bard.carradar.data.CrashLogStore
import tw.bard.carradar.databinding.ActivityCrashLogBinding

class CrashLogActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityCrashLogBinding
    private lateinit var store: CrashLogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.rootContainer)

        store = CrashLogStore(this)
        store.markRead()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCopyLogs.setOnClickListener {
            val text = store.readAll()
            if (text.isBlank()) {
                Toast.makeText(this, "目前無錯誤記錄", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CarRadarCrashLog", text))
            Toast.makeText(this, "已複製全部錯誤記錄", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            store.clear()
            render()
            Toast.makeText(this, "已清空錯誤記錄", Toast.LENGTH_SHORT).show()
        }

        render()
    }

    private fun render() {
        val text = store.readAll()
        binding.tvCrashLogs.text = text.ifBlank { "目前無錯誤記錄" }
    }
}
