package tw.bard.carradar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import tw.bard.carradar.data.AudioFocusMode
import tw.bard.carradar.data.Prefs
import tw.bard.carradar.data.VoiceClipSource
import tw.bard.carradar.data.VoicePackManager
import java.util.concurrent.ConcurrentLinkedQueue

class RadarAudioPlayer(
    private val context: Context,
    private val prefs: Prefs,
    private val voicePacks: VoicePackManager
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ConcurrentLinkedQueue<String>()
    private var currentPlayer: MediaPlayer? = null
    private var currentFocusRequest: AudioFocusRequest? = null
    private var isPlaying = false

    fun playPhrases(keys: List<String>, packIdOverride: String? = null) {
        if (keys.isEmpty()) return
        mainHandler.post {
            for (k in keys) {
                queue.add(if (packIdOverride != null) "$packIdOverride::$k" else k)
            }
            if (!isPlaying) {
                pumpNext()
            }
        }
    }

    fun release() {
        mainHandler.post {
            queue.clear()
            stopCurrentPlayer()
            abandonFocus()
            isPlaying = false
        }
    }

    private fun pumpNext() {
        val item = queue.poll()
        if (item == null) {
            isPlaying = false
            abandonFocus()
            return
        }
        isPlaying = true

        val (packId, key) = if (item.contains("::")) {
            val parts = item.split("::", limit = 2)
            parts[0] to parts[1]
        } else {
            prefs.activeVoicePackId to item
        }

        val source = voicePacks.resolveClip(packId, key)
        val focusMode = prefs.audioFocusMode
        if (focusMode == AudioFocusMode.DUCK) {
            requestDuckFocus()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (focusMode == AudioFocusMode.DUCK)
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                else
                    AudioAttributes.USAGE_MEDIA
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val mp = MediaPlayer()
        currentPlayer = mp
        try {
            mp.setAudioAttributes(attrs)
            when (source) {
                is VoiceClipSource.Asset -> {
                    context.assets.openFd(source.assetPath).use { afd ->
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
                is VoiceClipSource.LocalFile -> {
                    mp.setDataSource(source.file.absolutePath)
                }
            }
            mp.setOnCompletionListener {
                stopCurrentPlayer()
                pumpNext()
            }
            mp.setOnErrorListener { _, _, _ ->
                stopCurrentPlayer()
                pumpNext()
                true
            }
            mp.prepare()
            val vol = prefs.voiceVolumePct / 100f
            mp.setVolume(vol, vol)
            mp.start()
        } catch (_: Exception) {
            stopCurrentPlayer()
            pumpNext()
        }
    }

    private fun stopCurrentPlayer() {
        runCatching {
            currentPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        currentPlayer = null
    }

    private fun requestDuckFocus() {
        if (currentFocusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        currentFocusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        currentFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            currentFocusRequest = null
        }
    }
}
