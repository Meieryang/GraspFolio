package io.graspfolio.app

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Main-thread, foreground-only player. One document owns one player and one audio-focus request. */
internal class PageAudioPlayer(private val context: Context) : AutoCloseable {
    var selected by mutableStateOf<AudioNote?>(null); private set
    var expanded by mutableStateOf(false)
    var playing by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var position by mutableIntStateOf(0); private set
    var duration by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null
    private var prepared = false
    private var startWhenReady = false
    private var foreground = true
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause() }
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(focusListener).build() else null
    private var focused = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }
    init { ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED) }
    fun select(note: AudioNote) {
        if (selected?.id == note.id && player != null) {
            if (playing) expanded = true else if (!preparing) resume()
            return
        }
        expanded = false
        releasePlayer()
        selected = note; position = 0; duration = note.durationMs; error = null
        val file = AudioAssets(context).file(note)
        if (!file.exists() || file.length() != note.bytes) { error = "音频副本缺失，请重试同步或重新插入"; return }
        val media = MediaPlayer()
        player = media; preparing = true; startWhenReady = true
        try {
            media.setAudioAttributes(attributes)
            media.setDataSource(file.path)
            media.setOnPreparedListener {
                if (player !== media) return@setOnPreparedListener
                preparing = false; prepared = true; duration = media.duration.coerceAtLeast(1)
                if (startWhenReady && foreground) resume()
            }
            media.setOnCompletionListener {
                if (player === media) { playing = false; position = duration; abandonFocus() }
            }
            media.setOnErrorListener { _, _, _ ->
                if (player === media) { releasePlayer(); error = "无法播放此音频，请重新插入受支持的音频文件" }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) { releasePlayer(); error = "无法打开此音频，请重试或重新插入" }
    }
    private fun acquireFocus(): Boolean {
        if (focused) return true
        val result = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(focusRequest!!)
            else @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focused
    }
    fun resume() {
        if (!foreground) return
        if (!prepared) { if (!preparing) selected?.let(::select); return }
        try {
            if (!acquireFocus()) { error = "暂时无法播放，请稍后重试"; return }
            val media = player ?: return
            if (position >= duration) { media.seekTo(0); position = 0 }
            media.start(); playing = true; error = null
        } catch (_: Exception) { releasePlayer(); error = "音频播放失败，点击重试" }
    }
    fun pause() {
        startWhenReady = false
        if (prepared) runCatching { player?.pause(); position = player?.currentPosition ?: position }
        playing = false; abandonFocus()
    }
    fun toggle() { if (playing || preparing) pause() else resume() }
    fun seek(ms: Int) {
        if (!prepared) return
        position = ms.coerceIn(0, duration)
        runCatching { player?.seekTo(position) }.onFailure { error = "无法跳转到该位置" }
    }
    fun tick() { if (playing && prepared) runCatching { position = (player?.currentPosition ?: 0).coerceIn(0, duration) } }
    fun visiblePages(pages: List<Int>) { if (selected?.page?.let { it !in pages } == true) clear() }
    fun setForeground(value: Boolean) { foreground = value; if (!value) pause() }
    fun clear() { releasePlayer(); selected = null; expanded = false; error = null; position = 0; duration = 0 }
    private fun abandonFocus() {
        if (!focused) return
        if (Build.VERSION.SDK_INT >= 26) audioManager.abandonAudioFocusRequest(focusRequest!!)
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        focused = false
    }
    private fun releasePlayer() {
        startWhenReady = false; preparing = false; prepared = false; playing = false
        val old = player; player = null
        old?.release(); abandonFocus()
    }
    override fun close() { clear(); runCatching { context.unregisterReceiver(noisy) } }
}
