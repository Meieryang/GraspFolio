package io.graspfolio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object InkCodec {
    fun encode(identity: String, strokes: List<InkStroke>): String = JSONObject().put("version", 1).put("document", identity)
        .put("strokes", JSONArray().apply { strokes.forEach { s -> put(JSONObject().put("id", s.id).put("page", s.page)
            .put("color", s.color).put("width", s.width.toDouble()).put("brush", s.brush).put("points", JSONArray().apply {
                s.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble()).put(p.time)) }
            })) } }).toString()
    fun decode(text: String, identity: String): List<InkStroke> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1 && root.getString("document") == identity) { "批注版本或 PDF 身份不匹配" }
        val items = root.getJSONArray("strokes")
        val result = (0 until items.length()).map { i ->
            val s = items.getJSONObject(i); val pts = s.getJSONArray("points")
            val points = (0 until pts.length()).map { j -> val p = pts.getJSONArray(j)
                InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(), p.getLong(3)).also {
                    require(it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite())
                }
            }
            InkStroke(s.getString("id"), s.getInt("page"), points, s.getInt("color"), s.getDouble("width").toFloat(), s.getString("brush")).also {
                require(it.page >= 0 && it.points.isNotEmpty() && it.width.isFinite() && it.width > 0 && it.brush == "pressure")
            }
        }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }
}


/** Local durability never waits for the separate SAF worker. All coordination is local-queue-owned. */
internal class AnnotationStore internal constructor(private val context: Context, private val uri: Uri,
    private val backend: AnnotationBackend = SafAnnotationBackend(context, uri)) {
    companion object {
        private val stores = mutableMapOf<String, AnnotationStore>()
        @Synchronized fun obtain(context: Context, uri: Uri) = stores.getOrPut(uri.toString()) { AnnotationStore(context.applicationContext, uri) }
    }
    var strokes by mutableStateOf<List<InkStroke>>(emptyList()); private set
    var ready by mutableStateOf(false); private set
    var status by mutableStateOf("正在加载批注…"); private set
    private val localQueue = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "InkLocalWriter") }
    private val remoteQueue = Executors.newSingleThreadExecutor { r -> Thread(r, "InkSidecarSync").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("annotation_folders", Context.MODE_PRIVATE)
    private lateinit var journal: AnnotationJournal
    private var tree = prefs.getString(uri.toString(), null)?.let(Uri::parse)
    private var timer: ScheduledFuture<*>? = null
    private var checkpointTimer: ScheduledFuture<*>? = null
    private var burstStarted = 0L
    private var inFlight = false
    private var manualPending = false
    private var localFailed = false
    private var uiGeneration = 0L // Main thread only.
    private var durableGeneration = 0L // Local writer only.
    private val pendingWrites = AtomicInteger()
    private fun publish(message: String, generation: Long = durableGeneration) {
        main.post { if (uiGeneration == generation) status = message }
    }
    init {
        localQueue.execute {
            try {
                journal = InkPerformance.measure("local_recovery") {
                    backend.openJournal()
                }
                manualPending = true
                startSync()
            } catch (e: Exception) { publish("批注加载失败，已禁止书写以保护原数据：${e.message}") }
        }
    }
    fun replace(value: List<InkStroke>) {
        if (!ready) return
        strokes = value
        val generation = ++uiGeneration
        // Keep this label stable across successive strokes; failures remain visible.
        if (!status.startsWith("未同步")) status = "未同步 · 正在本地保护"
        val queuedAt = System.nanoTime()
        val pending = pendingWrites.incrementAndGet()
        localQueue.execute {
            try {
                val waitMs = (System.nanoTime() - queuedAt) / 1_000_000
                val bytes = InkPerformance.measure("local_delta") { journal.replace(value) }
                durableGeneration = generation; localFailed = false
                Log.i("GraspFolioPerf", "local_delta rev=${journal.state.revision} bytes=$bytes queueMs=$waitMs pending=$pending")
                publish(if (tree == null) "未同步 · 本地已保存，请授权 PDF 所在目录" else "未同步 · 本地已保存，等待旁文件同步")
                scheduleSync()
                checkpointTimer?.cancel(false)
                checkpointTimer = localQueue.schedule({
                    if (pendingWrites.get() == 0 && !localFailed) runCatching {
                        InkPerformance.measure("local_checkpoint") { journal.compactIfNeeded() }
                    }.onFailure { Log.w("GraspFolioPerf", "Local checkpoint deferred", it) }
                }, 2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                localFailed = true
                publish("未同步：本地保存失败，请勿退出：${e.message}", generation)
            } finally { pendingWrites.decrementAndGet() }
        }
    }
    fun authorize(folder: Uri) {
        if (!ready) return
        try {
            context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            requestManual(folder)
        } catch (e: Exception) { status = "未同步：目录授权失败：${e.message}" }
    }
    fun retry() { if (ready) requestManual(null) }
    private fun requestManual(folder: Uri?) {
        val latest = strokes
        val generation = uiGeneration
        ready = false
        localQueue.execute {
            try {
                // A prior failed delta may still only exist in the displayed state.
                if (localFailed) {
                    InkPerformance.measure("local_retry") { journal.replace(latest) }
                    localFailed = false; durableGeneration = generation
                }
                if (folder != null) {
                    tree = folder
                    prefs.edit().putString(uri.toString(), folder.toString()).apply()
                }
                manualPending = true
                timer?.cancel(false); timer = null
                startSync() // If busy, the old result is acknowledged first, then this runs.
            } catch (e: Exception) {
                publish("未同步：本地恢复保存失败，请勿退出：${e.message}", generation)
                main.post { ready = true }
            }
        }
    }
    /** Exit/background flush requests are asynchronous; the per-edit local records are already queued. */
    fun flush() { localQueue.execute { timer?.cancel(false); timer = null; if (::journal.isInitialized && journal.state.dirty && !localFailed) startSync() } }
    private fun scheduleSync() {
        if (tree == null || localFailed) return
        val now = System.nanoTime()
        if (burstStarted == 0L) burstStarted = now
        if (inFlight) return
        timer?.cancel(false)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(now - burstStarted)
        val delay = minOf(2000L, (5000L - elapsed).coerceAtLeast(0))
        timer = localQueue.schedule({ timer = null; startSync() }, delay, TimeUnit.MILLISECONDS)
    }
    private fun completeManual() {
        val loaded = journal.state.strokes
        val generation = durableGeneration
        main.post { if (uiGeneration == generation) strokes = loaded; ready = true }
    }
    private fun startSync() {
        if (inFlight || localFailed) return
        val manual = manualPending
        manualPending = false
        val folder = tree
        if (folder == null) {
            publish("未同步 · 本地已保存，请授权 PDF 所在目录")
            if (manual) completeManual()
            return
        }
        val snapshot = journal.state
        inFlight = true; burstStarted = 0
        // Capture immutable input. Remote work never reads the journal or blocks its executor.
        remoteQueue.execute {
            val result = runCatching { InkPerformance.measure("sidecar_total") { backend.sync(folder, snapshot, manual) } }
            localQueue.execute {
                inFlight = false
                result.fold(onSuccess = { output ->
                    try {
                        if (output.imported != null) {
                            require(journal.state.revision == snapshot.revision && !journal.state.dirty) { "本地状态已改变，未载入旁文件" }
                            journal.importRemote(output.imported, output.hash)
                        } else {
                            InkPerformance.measure("local_ack") { journal.acknowledge(snapshot.revision, output.hash) }
                        }
                        if (!localFailed) publish(if (journal.state.dirty) "未同步 · 本地已保存，等待旁文件同步" else "已同步到 PDF 旁")
                        // Compact only occasionally, not once per edit, and never before queued edits.
                        if (pendingWrites.get() == 0) {
                            runCatching { InkPerformance.measure("local_checkpoint") { journal.compactIfNeeded() } }
                                .onFailure { Log.w("GraspFolioPerf", "Local checkpoint deferred", it) }
                        }
                    } catch (e: Exception) {
                        localFailed = true
                        publish("未同步：同步确认保存失败，请通过菜单重试：${e.message}")
                    }
                }, onFailure = { failure ->
                    if (!localFailed) publish("未同步 · 本地保护中：${failure.message}")
                })
                if (manual && !manualPending) completeManual()
                if (manualPending && localFailed) {
                    // A disk failure in an older in-flight acknowledgement must not leave
                    // a subsequently requested authorization/retry permanently disabling input.
                    manualPending = false
                    completeManual()
                } else if (manualPending) startSync()
                else if (result.isSuccess && journal.state.dirty && !localFailed) scheduleSync()
                // On failure, retry only after another edit or explicit user action.
            }
        }
    }
}
