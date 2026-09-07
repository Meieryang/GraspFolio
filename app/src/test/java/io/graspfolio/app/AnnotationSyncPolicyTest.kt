package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class AnnotationSyncPolicyTest {
    @Test fun unchangedSidecarAllowsRetryOfPendingLocalChanges() {
        assertFalse(shouldLoadRemote("known", "known", true))
    }
    @Test fun firstAuthorizationLoadsExistingAnnotationsWhenLocalIsClean() {
        assertTrue(shouldLoadRemote(null, "existing", false))
    }
    @Test fun externalEditCannotOverwriteUnsyncedLocalData() {
        assertThrows(IllegalArgumentException::class.java) { shouldLoadRemote("known", "changed", true) }
        assertThrows(IllegalArgumentException::class.java) { shouldLoadRemote(null, "existing", true) }
    }
    @Test fun deletedSidecarIsNotSilentlyRecreated() {
        assertThrows(IllegalArgumentException::class.java) { shouldLoadRemote("known", null, true) }
        assertFalse(shouldLoadRemote(null, null, true))
    }
}
