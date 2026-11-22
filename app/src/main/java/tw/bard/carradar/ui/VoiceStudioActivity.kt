package tw.bard.carradar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tw.bard.carradar.audio.RadarAudioPlayer
import tw.bard.carradar.data.PhraseSlot
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.data.VoicePackManager
import tw.bard.carradar.databinding.ActivityVoiceStudioBinding
import tw.bard.carradar.databinding.ItemVoiceSlotBinding

class VoiceStudioActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityVoiceStudioBinding
    private lateinit var prefs: Prefs
    private lateinit var voicePacks: VoicePackManager
    private lateinit var audioPlayer: RadarAudioPlayer
    private lateinit var adapter: SlotAdapter

    private var recorder: MediaRecorder? = null
    private var pendingImportKey: String? = null

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要麥克風權限才能錄音", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val key = pendingImportKey ?: return@registerForActivityResult
        pendingImportKey = null
        if (uri == null) return@registerForActivityResult
        val packId = ensureWritablePackId()
        val ext = contentResolver.getType(uri)?.substringAfter("/") ?: "m4a"
        runCatching {
            contentResolver.openInputStream(uri)?.use { ins ->
                voicePacks.importClip(packId, key, ext, ins)
            }
            refreshUi()
            Toast.makeText(this, "已匯入", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "匯入失敗", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.rootContainer)

        prefs = Prefs(this)
        voicePacks = VoicePackManager(this)
        audioPlayer = RadarAudioPlayer(this, prefs, voicePacks)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnNewPack.setOnClickListener { showCreatePackDialog() }
        binding.btnSwitchPack.setOnClickListener { showSwitchPackDialog() }
        binding.btnDeletePack.setOnClickListener {
            val id = prefs.activeVoicePackId
            if (id != VoicePackManager.DEFAULT_PACK_ID) {
                voicePacks.deletePack(id)
                prefs.activeVoicePackId = VoicePackManager.DEFAULT_PACK_ID
                refreshUi()
            }
        }

        adapter = SlotAdapter()
        binding.recyclerSlots.layoutManager = LinearLayoutManager(this)
        binding.recyclerSlots.adapter = adapter
        refreshUi()
    }

    private fun refreshUi() {
        val packs = voicePacks.listPacks()
        val active = packs.firstOrNull { it.id == prefs.activeVoicePackId } ?: packs.first()
        prefs.activeVoicePackId = active.id
        binding.tvActivePack.text = active.name
        binding.btnDeletePack.visibility = if (active.isBuiltIn) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun ensureWritablePackId(): String {
        val current = prefs.activeVoicePackId
        if (VoicePackManager.BUILT_IN_PACKS.none { it.id == current }) return current
        val created = voicePacks.createPack("我的聲音")
        prefs.activeVoicePackId = created.id
        return created.id
    }

    private fun showCreatePackDialog() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = "例如：我的聲音、朋友阿明"
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        container.addView(input)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, tw.bard.carradar.R.style.Theme_CarRadar_Dialog)
            .setTitle("新增語音包")
            .setView(container)
            .setPositiveButton("建立") { _, _ ->
                val created = voicePacks.createPack(input.text.toString().trim())
                prefs.activeVoicePackId = created.id
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(tw.bard.carradar.R.drawable.bg_glass_dialog)
        dialog.show()
    }

    private fun showSwitchPackDialog() {
        val packs = voicePacks.listPacks()
        val names = packs.map { it.name }.toTypedArray()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, tw.bard.carradar.R.style.Theme_CarRadar_Dialog)
            .setTitle("選擇語音包")
            .setItems(names) { _, idx ->
                prefs.activeVoicePackId = packs[idx].id
                refreshUi()
            }
            .create()
        dialog.window?.setBackgroundDrawableResource(tw.bard.carradar.R.drawable.bg_glass_dialog)
        dialog.show()
    }

    private fun startRecording(key: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopRecording(save = false)
        val packId = ensureWritablePackId()
        val outFile = voicePacks.targetRecordFile(packId, key)
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(22050)
            mr.setAudioEncodingBitRate(96000)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
        }.onFailure {
            runCatching { mr.release() }
            Toast.makeText(this, "無法啟動錄音", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording(save: Boolean) {
        val mr = recorder ?: return
        recorder = null
        runCatching {
            mr.stop()
            mr.release()
        }.onFailure {
            runCatching { mr.release() }
        }
        if (save) {
            refreshUi()
        }
    }

    override fun onDestroy() {
        stopRecording(save = false)
        audioPlayer.release()
        super.onDestroy()
    }

    inner class SlotAdapter : RecyclerView.Adapter<SlotAdapter.Holder>() {
        private val items: List<PhraseSlot> = VoicePackManager.SLOTS

        inner class Holder(val b: ItemVoiceSlotBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemVoiceSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun getItemCount(): Int = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val slot = items[position]
            val packId = prefs.activeVoicePackId
            val hasCustom = voicePacks.hasCustomClip(packId, slot.key)

            holder.b.tvSlotLabel.text = slot.label
            holder.b.tvSlotStatus.text = if (hasCustom) "已自訂" else "預設男聲"
            holder.b.btnReset.visibility = if (hasCustom) View.VISIBLE else View.GONE

            holder.b.btnPlay.setOnClickListener {
                audioPlayer.playPhrases(listOf(slot.key), packId)
            }

            holder.b.btnRecord.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        holder.b.btnRecord.text = "錄音中..."
                        startRecording(slot.key)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        holder.b.btnRecord.text = "按住錄音"
                        stopRecording(save = true)
                        true
                    }
                    else -> false
                }
            }

            holder.b.btnImport.setOnClickListener {
                pendingImportKey = slot.key
                importLauncher.launch(arrayOf("audio/*"))
            }

            holder.b.btnReset.setOnClickListener {
                voicePacks.clearClip(packId, slot.key)
                refreshUi()
            }
        }
    }
}
