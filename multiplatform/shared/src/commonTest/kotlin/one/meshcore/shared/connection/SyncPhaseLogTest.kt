package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPhaseLogTest {
    @Test
    fun contactsPhaseMatchesIosFailedStateLine() {
        assertEquals("[Sync] State → .failed: radio down", syncPhaseFailLine(PHASE_CONTACTS, "radio down"))
    }

    @Test
    fun channelsPhaseMatchesIosPartialLine() {
        val line = syncPhaseFailLine(PHASE_CHANNELS, "timeout")
        assertTrue(line.contains("[Sync] Phase end: channels partial after thrown error"))
        assertTrue(line.contains("timeout"))
    }

    @Test
    fun messagesPhaseMatchesIosPollingLine() {
        val line = syncPhaseFailLine(PHASE_MESSAGES, "table full")
        assertEquals("[Sync] Message polling failed after contacts/channels: table full", line)
    }
}
