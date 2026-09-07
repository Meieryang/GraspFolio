package io.graspfolio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {
    @Test fun progressAndCoverSettingSurviveReopeningAndArePerDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = "test-progress-${System.nanoTime()}"
        val store = ReadingProgressStore(context)
        store.save(uri, ReadingProgress(17, false))
        assertEquals(ReadingProgress(17, false), ReadingProgressStore(context).load(uri))
        assertEquals(ReadingProgress(), store.load("$uri-other"))
        context.getSharedPreferences("reading_progress", 0).edit()
            .remove("$uri:page").remove("$uri:cover").commit()
    }
}
