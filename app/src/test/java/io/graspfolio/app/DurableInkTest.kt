package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class DurableInkTest {
    @Test fun oldSyncAcknowledgesOnlyItsSnapshot() {
        val state = DurableInk(revision = 8, syncedRevision = 2)
        val acknowledged = state.acknowledged(5, "hash")
        assertEquals(8L, acknowledged.revision); assertEquals(5L, acknowledged.syncedRevision)
        assertTrue(acknowledged.dirty)
    }
    @Test fun latestAckClearsDirtyButOutOfOrderAckCannotRegress() {
        val state = DurableInk(revision = 8, syncedRevision = 5).acknowledged(8, "hash")
        assertFalse(state.dirty)
        assertThrows(IllegalArgumentException::class.java) { state.acknowledged(5, "old") }
        assertThrows(IllegalArgumentException::class.java) { state.acknowledged(9, "future") }
    }
}
