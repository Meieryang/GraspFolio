package io.graspfolio.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises crash recovery and exit during finalization without capturing ambient microphone audio. */
class AudioRecordingRecoveryTest {
    @Test fun failedClipDoesNotBlockNextClipAndExitKeepsFinalizationAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://recording-recovery-test/$id")
        val directory = File(context.cacheDir, "recording-test-$id").apply { mkdirs() }
        val journalFile = File(directory, "state.json")
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(journalFile, id)
            override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean) = error("No folder authorized")
        }
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 10000
            while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(20)
            assertTrue(condition())
        }
        val prefix = inkDigest(uri.toString().toByteArray()) + "-"
        val pendingDir = File(context.filesDir, "audio-recordings").apply { mkdirs() }
        val broken = File(pendingDir, "${prefix}2-${UUID.randomUUID()}.m4a").apply { writeText("interrupted header"); setLastModified(1000) }
        val noteId = UUID.randomUUID().toString()
        val bytes = testAudioWav()
        val valid = File(pendingDir, "${prefix}4-$noteId.m4a").apply { writeBytes(bytes) }
        lateinit var store: AnnotationStore
        lateinit var recorder: PageAudioRecorder
        lateinit var reopenedRecorder: PageAudioRecorder
        onMain { store = AnnotationStore.obtain(context, uri, backend) }
        try {
            await { var ready = false; onMain { ready = store.ready }; ready }
            onMain {
                recorder = PageAudioRecorder(context, store)
                recorder.recoverPending()
                assertTrue(recorder.saving)
                recorder.close()
                reopenedRecorder = PageAudioRecorder(context, store)
                reopenedRecorder.recoverPending()
                reopenedRecorder.close()
                store.release()
            }
            await { store.isClosed }
            val saved = AnnotationJournal(journalFile, id).state.audio.single()
            assertEquals(noteId, saved.id)
            assertEquals(4, saved.page)
            assertEquals(inkDigest(bytes), saved.hash)
            assertFalse(valid.exists())
            assertTrue(broken.exists())
            onMain { assertNotNull(recorder.error); assertFalse(recorder.saving); assertFalse(reopenedRecorder.saving) }
        } finally {
            broken.delete(); valid.delete()
            File(context.filesDir, "audio-assets/${inkDigest(bytes)}.audio").delete()
            directory.deleteRecursively()
        }
    }
}
