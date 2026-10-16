package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagePollingDumpTest {
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun pollingLinesMatchIos() {
        assertEquals(
            "Message event monitoring started for radio A0000000-0000-0000-0000-000000000001",
            messageEventMonitoringStartedLine(radioId),
        )
        assertEquals(
            "Auto-fetch started for radio A0000000-0000-0000-0000-000000000001",
            autoFetchStartedLine(radioId),
        )
        assertEquals("Auto-fetch stopped", AUTO_FETCH_STOPPED)
        assertEquals("[Sync] Pausing auto-fetch for resync", PAUSING_AUTO_FETCH_FOR_RESYNC)
        assertEquals("[Sync] Resuming auto-fetch after resync", RESUMING_AUTO_FETCH_AFTER_RESYNC)
    }
}
