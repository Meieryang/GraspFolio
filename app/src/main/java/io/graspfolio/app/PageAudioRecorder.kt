package io.graspfolio.app

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Foreground capture. Finalization owns a store lease so leaving the reader cannot discard a recording. */
internal class PageAudioRecorder(private val context: Context, private val store: AnnotationStore) : AutoCloseable {
    companion object { private val finalization = Mutex() }
    var recording by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var elapsedMs by mutableStateOf(0); private set
    var page by mutableStateOf(0); private set
    private val prefix = inkDigest(store.documentKey.toByteArray()) + "-"
    private val directory get() = File(context.filesDir, "audio-recordings").apply { check(exists() || mkdirs()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var media: MediaRecorder? = null
    private var started = 0L
    private var held = false
    private var disposed = false
    fun start(targetPage: Int) {
        if (recording || saving || !store.ready) return
        store.retain(); held = true
        page = targetPage; error = null; elapsedMs = 0
        var file: File? = null
        try {
            file = File(directory, "$prefix$targetPage-${UUID.randomUUID()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            media = recorder
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setMaxFileSize(MAX_AUDIO_BYTES)
            recorder.setMaxDuration(2 * 60 * 60 * 1000)
            recorder.setOutputFile(file.path)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopAndSave()
            }
            recorder.setOnErrorListener { _, _, _ -> stopAndSave() }
            recorder.prepare(); recorder.start()
            started = SystemClock.elapsedRealtime(); recording = true
        } catch (_: Exception) {
            media?.release(); media = null
            if (file?.length() == 0L) file.delete()
            releaseLease(); error = "无法开始录音，请检查麦克风权限或其他应用是否正在使用麦克风"
        }
    }
    fun tick() { if (recording) elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun stopAndSave() {
        if (!recording) return
        recording = false
        val recorder = media; media = null
        try { recorder?.stop() }
        catch (_: Exception) { error = "录音过短或被中断，正在尝试保存" }
        finally { recorder?.release() }
        recoverPending(alreadyHeld = true)
    }
    fun recoverPending(alreadyHeld: Boolean = false) {
        if (saving || recording) return
        val files = try {
            directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.extension == "m4a" }.sortedBy { it.lastModified() }
        } catch (failure: Exception) {
            error = "无法读取待保存录音：${failure.message}"
            if (alreadyHeld) releaseLease()
            return
        }
        if (files.isEmpty()) { error = null; if (alreadyHeld) releaseLease(); return }
        if (!alreadyHeld) { store.retain(); held = true }
        saving = true
        scope.launch {
            try {
                // A recreated reader can overlap its predecessor while the latter finishes saving.
                finalization.withLock {
                    withTimeout(15000) { while (!store.ready) delay(100) }
                    var failed = 0
                    for (file in files) {
                        if (!file.exists()) continue // Already committed by the previous reader.
                        try {
                            val suffix = file.name.removePrefix(prefix).removeSuffix(".m4a")
                            val page = suffix.substringBefore('-').toIntOrNull() ?: error("录音页面信息损坏")
                            val id = suffix.substringAfter('-')
                            val name = "录音 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                            val note = store.audio.firstOrNull { it.id == id }
                                ?: AudioAssets(context).import(Uri.fromFile(file), page, name, id)
                            withTimeout(15000) { while (!store.ready) delay(100) }
                            val durable = suspendCancellableCoroutine<Boolean> { continuation ->
                                store.addAudio(note) { saved -> if (continuation.isActive) continuation.resume(saved) }
                            }
                            check(durable) { "本地关联保存失败" }
                            file.delete() // Only after the audio reference is committed to the journal.
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed++ }
                    }
                    error = if (failed == 0) null else "$failed 段录音尚未保存，原始文件已保留，可稍后重试"
                }
            } catch (failure: Exception) { error = "录音尚未保存，原始录音已保留，请重试恢复：${failure.message ?: "读取失败"}" }
            finally { saving = false; releaseLease(); if (disposed) scope.cancel() }
        }
    }
    fun dismissError() { error = null }
    private fun releaseLease() { if (held) { held = false; store.release() } }
    override fun close() { disposed = true; if (recording) stopAndSave(); if (!saving) { releaseLease(); scope.cancel() } }
}
