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

internal data class InkDocument(val strokes: List<InkStroke>, val progress: ReadingProgress?, val audio: List<AudioNote> = emptyList())

internal object InkCodec {
    fun encodeDocument(identity: String, strokes: List<InkStroke>, progress: ReadingProgress?, audio: List<AudioNote> = emptyList()): String =
        root(identity, strokes).put("version", if (audio.isEmpty()) 2 else 3).put("reading", progress?.toJson() ?: JSONObject.NULL)
            .apply { if (audio.isNotEmpty()) put("audio", audioJson(audio)) }.toString()
    fun encode(identity: String, strokes: List<InkStroke>): String = root(identity, strokes).toString()
    private fun root(identity: String, strokes: List<InkStroke>): JSONObject = JSONObject().put("version", 1).put("document", identity)
        .put("strokes", JSONArray().apply { strokes.forEach { s -> put(JSONObject().put("id", s.id).put("page", s.page)
            .put("color", s.color).put("width", s.width.toDouble()).put("brush", s.brush).put("points", JSONArray().apply {
                s.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble()).put(p.time)) }
            })) } })
    fun decode(text: String, identity: String): List<InkStroke> = decodeDocument(text, identity).strokes
    fun decodeDocument(text: String, identity: String): InkDocument {
        val root = JSONObject(text)
        require(root.getInt("version") in 1..3 && root.getString("document") == identity) { "批注版本或 PDF 身份不匹配" }
        val reading = if (root.getInt("version") >= 2) { require(root.has("reading")); readProgress(root) } else null
        val items = root.getJSONArray("strokes")
        val result = (0 until items.length()).map { i ->
            val s = items.getJSONObject(i); val pts = s.getJSONArray("points")
            val points = (0 until pts.length()).map { j -> val p = pts.getJSONArray(j)
                InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(), p.getLong(3)).also {
                    require(it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite())
                }
            }
            InkStroke(s.getString("id"), s.getInt("page"), points, s.getInt("color"), s.getDouble("width").toFloat(), s.getString("brush")).also {
                require(it.page >= 0 && it.points.isNotEmpty() && it.width.isFinite() && it.width > 0 && it.brush in BrushStyle.types)
            }
        }
        require(result.map { it.id }.distinct().size == result.size)
        return InkDocument(result, reading, if (root.getInt("version") >= 3) readAudio(root.getJSONArray("audio")) else emptyList())
    }
}


/** Local durability never waits for the separate SAF worker. All coordination is local-queue-owned. */
internal class AnnotationStore internal constructor(private val context: Context, private val uri: Uri,
    private val backend: AnnotationBackend = SafAnnotationBackend(context, uri)) {
    companion object {
        private val stores = mutableMapOf<String, AnnotationStore>()
        @Synchronized fun obtain(context: Context, uri: Uri, backend: AnnotationBackend = SafAnnotationBackend(context.applicationContext, uri)): AnnotationStore {
            val store = stores.getOrPut(uri.toString()) { AnnotationStore(context.applicationContext, uri, backend) }
            val restart = store.loadCancelled
            store.managed = true
            store.loadCancelled = false
            store.readers++
            if (restart && !store.ready) store.localQueue.execute { store.initialize() }
            return store
        }
    }
    internal val documentKey get() = uri.toString()
    internal fun retain() = synchronized(Companion) {
        check(!retired)
        readers++
    }
    private var readers = 0 // Protected by the companion monitor.
    private var retired = false
    internal val isClosed get() = localQueue.isShutdown && remoteQueue.isShutdown
    var strokes by mutableStateOf<List<InkStroke>>(emptyList()); private set
    private var resident: ResidentInk? = null
    private var requestedPages: Set<Int> = setOf(0, 1)
    var loadedPages by mutableStateOf<Set<Int>>(emptySet()); private set
    private var displayedPages: Set<Int> = setOf(0, 1)
    var loading by mutableStateOf(DocumentLoading("正在校验文档并恢复批注")); private set
    @Volatile private var loadCancelled = false
    private var managed = false
    // Writer-owned: failed edits on any page must survive navigation and later retries.
    private val unsavedPages = mutableMapOf<Int, List<InkStroke>>()
    fun showPages(pages: Set<Int>) {
        requestedPages = pages
        if (!ready || loadedPages == pages) return
        strokes = resident?.window(pages).orEmpty()
        loadedPages = pages; displayedPages = pages
    }
    private fun loadProgress(value: DocumentLoading) {
        if (!loadCancelled) main.post { if (!loadCancelled && !retired) loading = value }
    }
    private fun loadFailure(message: String) {
        main.post { if (!loadCancelled && !retired) {
            ready = false; loading = DocumentLoading("打开失败", error = message)
            status = message
        } }
    }
    fun retryLoad() {
        if (loading.error == null || retired) return
        loading = DocumentLoading("正在重新加载批注")
        if (::journal.isInitialized) requestManual(null) else localQueue.execute { initialize() }
    }
    var progress by mutableStateOf<ReadingProgress?>(null); private set
    var audio by mutableStateOf<List<AudioNote>>(emptyList()); private set
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
    init { localQueue.execute { initialize() } }
    private fun initialize() {
        try {
            if (!::journal.isInitialized) journal = InkPerformance.measure("local_recovery") { backend.openJournal() }
            if (loadCancelled) return
            manualPending = true
            startSync()
        } catch (e: Exception) { loadFailure("批注加载失败，原数据已保留：${e.message}") }
        catch (_: OutOfMemoryError) { loadFailure(InsufficientInkMemory().message!!) }
    }
    /** Keep the same coordinator during a rapid reopen; retire only after queued work drains. */
    fun release() {
        synchronized(Companion) {
            check(readers > 0)
            readers--
            if (managed && readers == 0) loadCancelled = true
            localQueue.execute {
                timer?.cancel(false); timer = null
                if (::journal.isInitialized && journal.state.dirty && !localFailed) startSync()
                retireIfIdle()
            }
        }
    }
    private fun retireIfIdle() {
        synchronized(Companion) {
            // A failed local write can contain the only copy in memory; preserve it for retry.
            if (readers != 0 || retired || inFlight || pendingWrites.get() != 0 || timer != null || localFailed) return
            if (stores[uri.toString()] !== this) return
            stores.remove(uri.toString())
            retired = true
            main.post { resident = null; strokes = emptyList(); loadedPages = emptySet(); ready = false }
            checkpointTimer?.cancel(false); checkpointTimer = null
            remoteQueue.shutdown()
            localQueue.shutdown()
        }
    }
    fun replace(value: List<InkStroke>) = update(value, progress)
    fun saveProgress(value: ReadingProgress) {
        if (!ready || progress == value) return
        require(value.page >= 0)
        update(strokes, value)
        ReadingProgressStore(context).save(uri.toString(), value)
    }
    fun addAudio(note: AudioNote, onDurable: (Boolean) -> Unit = {}) {
        check(ready) { "文档正在同步，请稍后重新插入" }
        val existing = audio.firstOrNull { it.id == note.id }
        require(existing == null || existing == note)
        require(existing != null || audio.size < 1000)
        update(strokes, progress, if (existing == null) audio + note else audio, onDurable)
    }
    fun removeAudio(id: String) { if (ready) update(strokes, progress, audio.filterNot { it.id == id }) }
    private fun update(value: List<InkStroke>, reading: ReadingProgress?, notes: List<AudioNote> = audio, onDurable: (Boolean) -> Unit = {}) {
        if (!ready) return
        val pages = displayedPages
        val inkChanged = value !== strokes
        if (inkChanged) resident?.replace(pages, value)
        strokes = value
        progress = reading
        audio = notes
        val generation = ++uiGeneration
        // Keep this label stable across successive strokes; failures remain visible.
        if (!status.startsWith("未同步")) status = "未同步 · 正在本地保护"
        val queuedAt = System.nanoTime()
        val pending = pendingWrites.incrementAndGet()
        localQueue.execute {
            try {
                val waitMs = (System.nanoTime() - queuedAt) / 1_000_000
                if (inkChanged) {
                    val grouped = value.groupBy { it.page }
                    pages.forEach { unsavedPages[it] = grouped[it].orEmpty() }
                }
                val bytes = InkPerformance.measure("local_delta") { persistPending(reading, notes) }
                durableGeneration = generation; localFailed = false
                main.post { onDurable(true) }
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
                main.post { onDurable(false) }
                publish("未同步：本地保存失败，请勿退出：${e.message}", generation)
            } finally { pendingWrites.decrementAndGet() }
        }
    }
    private fun persistPending(reading: ReadingProgress?, notes: List<AudioNote>): Int {
        val bytes = if (unsavedPages.isEmpty()) journal.replace(journal.state.strokes, reading, notes)
            else journal.replacePages(unsavedPages.keys.toSet(), unsavedPages.toSortedMap().values.flatten(), reading, notes)
        unsavedPages.clear()
        return bytes
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
        val latestProgress = progress
        val latestAudio = audio
        val generation = uiGeneration
        ready = false
        resident = null; strokes = emptyList(); loadedPages = emptySet()
        loading = DocumentLoading("正在校验旁文件")
        localQueue.execute {
            try {
                // A prior failed delta may still only exist in the displayed state.
                if (localFailed) {
                    InkPerformance.measure("local_retry") { persistPending(latestProgress, latestAudio) }
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
                loadFailure("本地恢复保存失败，原修改已保留：${e.message}")
            }
        }
    }
    /** Exit/background flush requests are asynchronous; the per-edit local records are already queued. */
    fun flush() = synchronized(Companion) {
        if (!retired) localQueue.execute {
            timer?.cancel(false); timer = null
            if (::journal.isInitialized && journal.state.dirty && !localFailed) startSync()
        }
    }
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
        val legacy = ReadingProgressStore(context)
        if (journal.state.progress == null && legacy.contains(uri.toString())) {
            try {
                journal.replace(journal.state.strokes, legacy.load(uri.toString()))
                publish("未同步 · 已迁移本机阅读设置，等待同步")
                scheduleSync()
            } catch (failure: Exception) {
                publish("未同步 · 阅读设置迁移失败，原本机设置已保留：${failure.message}")
            }
        }
        if (loadCancelled) return
        if (localFailed) { loadFailure("本地保存未完成，请重试。原修改已保留。"); return }
        val loaded = journal.state
        val generation = durableGeneration
        try {
            val cached = InkPerformance.measure("book_ink_preload") {
                ResidentInk.load(loaded.strokes, cancelled = { loadCancelled }, onProgress = ::loadProgress)
            }
            main.post {
                if (!loadCancelled && !retired && uiGeneration == generation) {
                    resident = cached
                    strokes = cached.window(requestedPages)
                    loadedPages = requestedPages; displayedPages = requestedPages
                    progress = loaded.progress; audio = loaded.audio
                    loaded.progress?.let { legacy.save(uri.toString(), it) }
                    loading = DocumentLoading("全书批注已就绪", loaded.strokes.size, loaded.strokes.size)
                    ready = true
                }
            }
        } catch (_: java.util.concurrent.CancellationException) { /* Release drains durable work separately. */ }
        catch (_: OutOfMemoryError) { loadFailure(InsufficientInkMemory().message!!) }
        catch (e: Exception) { loadFailure("批注加载失败，原数据已保留：${e.message}") }
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
        if (manual) loadProgress(DocumentLoading("正在校验旁文件与附件"))
        inFlight = true; burstStarted = 0
        // Capture immutable input. Remote work never reads the journal or blocks its executor.
        remoteQueue.execute {
            val result = runCatching { InkPerformance.measure("sidecar_total") { backend.sync(folder, snapshot, manual) } }
            localQueue.execute {
                inFlight = false
                result.fold(onSuccess = { output ->
                    try {
                        if (output.imported != null) {
                            require(journal.state.revision == snapshot.revision && journal.state.canImportRemote) { "本地状态已改变，未载入旁文件" }
                            journal.importRemote(output.imported, output.hash, output.progress, output.audio)
                        } else {
                            InkPerformance.measure("local_ack") { journal.acknowledge(output.acknowledgedRevision ?: snapshot.revision, output.hash) }
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
                retireIfIdle()
            }
        }
    }
}
