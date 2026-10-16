package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncResyncDumpTest {
    @Test
    fun resyncLoopLinesMatchIos() {
        val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        assertEquals(
            "Initial sync did not produce usable contacts, starting resync loop",
            INITIAL_SYNC_NO_USABLE_CONTACTS,
        )
        assertEquals("Resync attempt 1/3", resyncAttemptLine(1, 3))
        assertEquals("Resync succeeded", RESYNC_SUCCEEDED)
        assertEquals("Resync failed 3 times, disconnecting", resyncFailedTimesLine(3))
        assertEquals(
            "Attempting resync for device A0000000-0000-0000-0000-000000000001",
            attemptingResyncLine(radioId),
        )
        assertEquals(
            "Resync completed without usable contacts",
            RESYNC_COMPLETED_WITHOUT_USABLE_CONTACTS,
        )
        assertEquals("Resync failed: radio down", resyncFailedLine("radio down"))
    }
}
