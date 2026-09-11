package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class SidecarTransactionTest {
    private class MemoryFiles : SidecarFiles {
        val data = linkedMapOf<String, ByteArray>()
        var mutations = 0
        var crashAfter = Int.MAX_VALUE
        var renameSupported = true
        private fun changed() { mutations++; if (mutations == crashAfter) error("power loss") }
        override fun read(name: String) = data[name]?.copyOf()
        override fun create(name: String, bytes: ByteArray) {
            check(name !in data)
            data[name] = bytes.copyOf(bytes.size / 2); changed()
            data[name] = bytes.copyOf(); changed()
        }
        override fun canRename(name: String) = renameSupported
        override fun rename(from: String, to: String) {
            check(to !in data)
            data[to] = checkNotNull(data.remove(from)); changed()
        }
        override fun delete(name: String) { if (data.remove(name) != null) changed() }
    }
    private class MemoryLog : SidecarTransactionLog {
        var pending: PendingSidecar? = null
        override fun load() = pending
        override fun save(value: PendingSidecar) { pending = value }
        override fun clear() { pending = null }
    }
    private val name = "book.graspfolio"
    private val old = "old complete content".toByteArray()
    private val next = "new complete content".toByteArray()

    @Test fun interruptionAtEachMutationLeavesOldOrCompleteNewAndCanRetry() {
        for (crash in 1..4) {
            val files = MemoryFiles().apply { data[name] = old; crashAfter = crash }
            val log = MemoryLog()
            assertThrows(Exception::class.java) { SidecarTransaction(files, log, name).write(inkDigest(old), next, 7) }
            files.crashAfter = Int.MAX_VALUE
            val fresh = SidecarTransaction(files, log, name)
            val recovered = fresh.recover(inkDigest(old), 9, 6)
            if (recovered != null) {
                assertEquals(7L, recovered.revision)
                assertArrayEquals(next, files.read(name))
                fresh.recover(recovered.hash, 9, 7)
            } else {
                assertArrayEquals(old, files.read(name))
                fresh.write(inkDigest(old), next, 9)
                fresh.recover(inkDigest(next), 9, 9)
            }
            assertArrayEquals(next, files.read(name))
            assertEquals(setOf(name), files.data.keys)
            assertNull(log.pending)
        }
    }
    @Test fun firstCreationAndLostAcknowledgementRecoverWithoutConflict() {
        for (crash in 1..3) {
            val files = MemoryFiles().apply { crashAfter = crash }
            val log = MemoryLog()
            assertThrows(Exception::class.java) { SidecarTransaction(files, log, name).write(null, next, 1) }
            files.crashAfter = Int.MAX_VALUE
            val tx = SidecarTransaction(files, log, name)
            val result = tx.recover(null, 1, 0)
            if (result == null) { assertNull(files.read(name)); tx.write(null, next, 1) }
            else assertEquals(1L, result.revision)
            tx.recover(inkDigest(next), 1, 1)
            assertArrayEquals(next, files.read(name))
        }
    }
    @Test fun externalChangeAfterStagingIsNeverOverwritten() {
        val files = MemoryFiles().apply { data[name] = old; crashAfter = 2 }
        val log = MemoryLog()
        assertThrows(Exception::class.java) { SidecarTransaction(files, log, name).write(inkDigest(old), next, 1) }
        val external = "someone else's edit".toByteArray()
        files.data[name] = external; files.crashAfter = Int.MAX_VALUE
        assertThrows(Exception::class.java) { SidecarTransaction(files, log, name).recover(inkDigest(old), 1, 0) }
        assertArrayEquals(external, files.read(name))
        assertNotNull(log.pending)
    }
    @Test fun noRenameSupportNeverTruncatesExistingSidecar() {
        val files = MemoryFiles().apply { data[name] = old; renameSupported = false }
        val log = MemoryLog()
        assertThrows(Exception::class.java) { SidecarTransaction(files, log, name).write(inkDigest(old), next, 1) }
        assertArrayEquals(old, files.read(name)); assertEquals(0, files.mutations)
        assertNull(log.pending)
    }
    @Test fun backupRemainsUntilDurableAcknowledgement() {
        val files = MemoryFiles().apply { data[name] = old }
        val log = MemoryLog(); val tx = SidecarTransaction(files, log, name)
        tx.write(inkDigest(old), next, 7)
        assertArrayEquals(old, files.read(log.pending!!.backup))
        assertEquals(7L, tx.recover(inkDigest(old), 8, 6)!!.revision)
        assertNotNull(files.read(log.pending!!.backup))
        tx.recover(inkDigest(next), 8, 7)
        assertEquals(setOf(name), files.data.keys)
    }

    @Test fun externalEditAfterAcknowledgementReturnsToNormalConflictPolicy() {
        val files = MemoryFiles().apply { data[name] = old }
        val log = MemoryLog(); val tx = SidecarTransaction(files, log, name)
        tx.write(inkDigest(old), next, 7)
        val external = "edit on another device".toByteArray()
        files.data[name] = external
        assertNull(tx.recover(inkDigest(next), 7, 7))
        assertNull(log.pending)
        assertArrayEquals(external, files.read(name))
        assertTrue(shouldLoadRemote(inkDigest(next), inkDigest(external), false))
        assertThrows(Exception::class.java) { shouldLoadRemote(inkDigest(next), inkDigest(external), true) }
    }
}
