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
}
