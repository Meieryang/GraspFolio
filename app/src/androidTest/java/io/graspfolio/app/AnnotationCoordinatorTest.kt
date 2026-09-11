package io.graspfolio.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class AnnotationCoordinatorTest {
    @org.junit.Before fun keepFixtureInForeground() {
        // The runner closes Activities after each test, so restore the fixture for each case.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity"
        )).use { it.readBytes() }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!check()) { assertTrue("Timed out waiting for save coordinator", System.nanoTime() < end); Thread.sleep(10) }
    }
    private fun status(store: AnnotationStore): String { var result = ""; onMain { result = store.status }; return result }
    private fun stroke(id: String) = InkStroke(id, 0, listOf(InkPoint(1f, 2f, .5f, 10)))

    @Test fun slowRemoteDoesNotBlockLocalAndOldAckDoesNotMarkNewInkSynced() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }
        val file = File(dir, "state.json")
        val prefs = context.getSharedPreferences("annotation_folders", 0)
        prefs.edit().putString(uri.toString(), "content://test/tree/folder").commit()
        val firstEntered = CountDownLatch(1); val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1); val secondRelease = CountDownLatch(1)
        val snapshots = CopyOnWriteArrayList<Long>()
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(file, "test-pdf")
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
                snapshots += snapshot.revision
                if (snapshot.revision == 1L) { firstEntered.countDown(); check(firstRelease.await(8, TimeUnit.SECONDS)) }
                if (snapshot.revision == 2L) { secondEntered.countDown(); check(secondRelease.await(8, TimeUnit.SECONDS)) }
                return SidecarResult("hash-${snapshot.revision}")
            }
        }
        lateinit var store: AnnotationStore
        try {
            onMain { store = AnnotationStore(context, uri, backend) }
            eventually { var ready = false; onMain { ready = store.ready }; ready }
            val a = stroke("a"); val b = stroke("b")
            onMain { store.replace(listOf(a)); store.flush() }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            onMain { store.replace(listOf(a, b)) }
            eventually { AnnotationJournal(file, "test-pdf").state.revision == 2L }
            assertEquals(listOf(a, b), AnnotationJournal(file, "test-pdf").state.strokes)
            firstRelease.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            assertTrue(AnnotationJournal(file, "test-pdf").state.dirty)
            assertFalse(status(store).startsWith("已同步"))
            secondRelease.countDown()
            eventually { status(store).startsWith("已同步") }
            assertEquals(listOf(0L, 1L, 2L), snapshots.toList())
            assertFalse(AnnotationJournal(file, "test-pdf").state.dirty)
        } finally {
            firstRelease.countDown(); secondRelease.countDown()
            prefs.edit().remove(uri.toString()).commit()
            // Dedicated test directory only; no real PDF or recovery data is used.
            dir.deleteRecursively()
        }
    }

    @Test fun burstEditsCoalesceAndRemoteFailureRetainsRecovery() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString(); val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }; val file = File(dir, "state.json")
        val prefs = context.getSharedPreferences("annotation_folders", 0)
        prefs.edit().putString(uri.toString(), "content://test/tree/folder").commit()
        val snapshots = CopyOnWriteArrayList<Long>()
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(file, "test-pdf")
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
                snapshots += snapshot.revision
                if (snapshot.dirty) error("simulated revoked permission")
                return SidecarResult("initial")
            }
        }
        lateinit var store: AnnotationStore
        try {
            onMain { store = AnnotationStore(context, uri, backend) }
            eventually { var ready = false; onMain { ready = store.ready }; ready }
            val all = (1..12).map { stroke("$it") }
            onMain { for (n in 1..12) store.replace(all.take(n)) }
            eventually { status(store).contains("simulated revoked permission") }
            assertEquals(listOf(0L, 12L), snapshots.toList())
            val restored = AnnotationJournal(file, "test-pdf").state
            assertEquals(all, restored.strokes); assertTrue(restored.dirty)
        } finally { prefs.edit().remove(uri.toString()).commit(); dir.deleteRecursively() }
    }

    @Test fun releaseDrainsWritesAndRapidReopenReusesCoordinator() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }
        val file = File(dir, "state.json")
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val backend = object : AnnotationBackend {
            override fun openJournal(): AnnotationJournal {
                entered.countDown()
                check(resume.await(8, TimeUnit.SECONDS))
                return AnnotationJournal(file, "test-pdf")
            }
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean) = error("No folder authorized")
        }
        lateinit var first: AnnotationStore
        lateinit var reopened: AnnotationStore
        try {
            onMain { first = AnnotationStore.obtain(context, uri, backend) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onMain {
                first.release()
                reopened = AnnotationStore.obtain(context, uri, backend)
                assertSame(first, reopened)
            }
            resume.countDown()
            eventually { var ready = false; onMain { ready = reopened.ready }; ready }
            val ink = listOf(stroke("saved-before-exit"))
            onMain { reopened.replace(ink); reopened.release() }
            eventually { first.isClosed }
            assertEquals(ink, AnnotationJournal(file, "test-pdf").state.strokes)
            onMain {
                reopened = AnnotationStore.obtain(context, uri, backend)
                assertNotSame(first, reopened)
            }
            eventually { var ready = false; onMain { ready = reopened.ready }; ready }
            onMain { assertEquals(ink, reopened.strokes); reopened.release() }
            eventually { reopened.isClosed }
        } finally { resume.countDown() }
        dir.deleteRecursively()
    }

    @Test fun releaseDuringRemoteSyncKeepsQueuedInkUntilFinalAcknowledgement() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }
        val file = File(dir, "state.json")
        val prefs = context.getSharedPreferences("annotation_folders", 0)
        prefs.edit().putString(uri.toString(), "content://test/tree/folder").commit()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(file, "test-pdf")
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
                if (snapshot.revision == 1L) { entered.countDown(); check(resume.await(8, TimeUnit.SECONDS)) }
                return SidecarResult("hash-${snapshot.revision}")
            }
        }
        lateinit var store: AnnotationStore
        try {
            onMain { store = AnnotationStore.obtain(context, uri, backend) }
            eventually { var ready = false; onMain { ready = store.ready }; ready }
            val a = stroke("a"); val b = stroke("b")
            onMain { store.replace(listOf(a)); store.flush() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onMain { store.replace(listOf(a, b)); store.release() }
            eventually { AnnotationJournal(file, "test-pdf").state.revision == 2L }
            assertFalse(store.isClosed)
            resume.countDown()
            eventually { store.isClosed }
            val restored = AnnotationJournal(file, "test-pdf").state
            assertEquals(listOf(a, b), restored.strokes)
            assertFalse(restored.dirty)
        } finally { resume.countDown(); prefs.edit().remove(uri.toString()).commit() }
        dir.deleteRecursively()
    }

    @Test fun importedReadingStateThenReadingOnlyChangeSyncsAndReopens() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString(); val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }
        val file = File(dir, "state.json")
        val prefs = context.getSharedPreferences("annotation_folders", 0)
        prefs.edit().putString(uri.toString(), "content://test/tree/folder").commit()
        val snapshots = CopyOnWriteArrayList<DurableInk>()
        val ink = listOf(stroke("imported"))
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(file, "pdf")
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
                snapshots += snapshot
                return if (snapshot.base == null) SidecarResult("remote", ink, ReadingProgress(12, false))
                else SidecarResult("saved-${snapshot.revision}")
            }
        }
        lateinit var store: AnnotationStore
        onMain { store = AnnotationStore.obtain(context, uri, backend) }
        try {
            eventually { var ready = false; onMain { ready = store.ready }; ready }
            onMain {
                assertEquals(ReadingProgress(12, false), store.progress)
                assertEquals(ink, store.strokes)
                store.saveProgress(ReadingProgress(31, true))
                store.release()
            }
            eventually { store.isClosed }
            val saved = snapshots.last()
            assertEquals(ReadingProgress(31, true), saved.progress)
            assertEquals(ink, saved.strokes)
            val restored = AnnotationJournal(file, "pdf").state
            assertEquals(saved.progress, restored.progress)
            assertFalse(restored.dirty)
        } finally {
            prefs.edit().remove(uri.toString()).commit()
            context.getSharedPreferences("reading_progress", 0).edit().remove("$uri:page").remove("$uri:cover").commit()
        }
        dir.deleteRecursively()
    }

    @Test fun legacyPreferencesAreMigratedAfterImportingVersionOneInk() {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString(); val uri = Uri.parse("content://graspfolio-save-test/$id")
        val dir = File(context.cacheDir, "coordinator-test-$id").apply { check(mkdir()) }
        val file = File(dir, "state.json")
        val folders = context.getSharedPreferences("annotation_folders", 0)
        folders.edit().putString(uri.toString(), "content://test/tree/folder").commit()
        val reading = ReadingProgress(42, false)
        ReadingProgressStore(context).save(uri.toString(), reading)
        val ink = listOf(stroke("legacy"))
        val snapshots = CopyOnWriteArrayList<DurableInk>()
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(file, "pdf")
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
                snapshots += snapshot
                return if (snapshot.base == null) SidecarResult("v1", ink)
                else SidecarResult("v2-${snapshot.revision}")
            }
        }
        lateinit var store: AnnotationStore
        onMain { store = AnnotationStore.obtain(context, uri, backend) }
        try {
            eventually { var ready = false; onMain { ready = store.ready }; ready }
            onMain { assertEquals(ink, store.strokes); assertEquals(reading, store.progress); store.release() }
            eventually { store.isClosed }
            assertEquals(reading, snapshots.last().progress)
            assertEquals(ink, AnnotationJournal(file, "pdf").state.strokes)
            assertFalse(AnnotationJournal(file, "pdf").state.dirty)
        } finally {
            folders.edit().remove(uri.toString()).commit()
            context.getSharedPreferences("reading_progress", 0).edit().remove("$uri:page").remove("$uri:cover").commit()
        }
        dir.deleteRecursively()
    }
}
