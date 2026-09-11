package io.graspfolio.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderChromeTest {
    @Test fun menuRoundTripShowsWritingTools() {
        assertEquals(ReaderChrome.MENU, ReaderChrome.READING.onBack())
        assertEquals(ReaderChrome.WRITING, ReaderChrome.READING.onBack().onBack())
    }

    @Test fun enteringMenuReplacesWritingTools() {
        assertEquals(ReaderChrome.MENU, ReaderChrome.WRITING.onBack())
        assertEquals(ReaderChrome.WRITING, ReaderChrome.WRITING.onBack().onBack())
    }

    @Test fun manuallyClosedToolbarReopensAfterMenuRoundTrip() {
        val afterClose = ReaderChrome.READING
        assertEquals(ReaderChrome.WRITING, afterClose.onBack().onBack())
    }
}
