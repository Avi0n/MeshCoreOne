package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncTimelineDumpTest {
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun syncTimelineLinesMatchIos() {
        assertEquals("[Sync] State → .syncing(.contacts)", syncStateSyncingLine(PHASE_CONTACTS))
        assertEquals("[Sync] State → .syncing(.channels)", syncStateSyncingLine(PHASE_CHANNELS))
        assertEquals("[Sync] State → .syncing(.messages)", syncStateSyncingLine(PHASE_MESSAGES))
        assertEquals("[Sync] State → .synced", SYNC_STATE_SYNCED)
        assertEquals("[Sync] State → .idle (disconnected)", SYNC_STATE_IDLE_DISCONNECTED)
        assertEquals("Disconnected, sync state reset to idle", SYNC_DISCONNECTED_RESET)
        assertEquals(
            "isSyncInProgress still true at disconnect — clearing as safety net",
            SYNC_IN_PROGRESS_AT_DISCONNECT,
        )
        assertEquals(
            "[Sync] Skipping channel sync (recent clean or attempted sync)",
            SYNC_SKIP_CHANNEL,
        )
        assertEquals(
            "[Sync] Skipping channel sync (app in background)",
            SYNC_SKIP_CHANNEL_BACKGROUND,
        )
    }

    @Test
    fun syncPhaseLinesMatchIos() {
        assertEquals(
            "Connection established for device A0000000-0000-0000-0000-000000000001",
            connectionEstablishedLine(radioId),
        )
        assertEquals(
            "Starting full sync for device A0000000-0000-0000-0000-000000000001",
            startingFullSyncLine(radioId),
        )
        assertEquals(
            "[Sync] Phase start: contacts (incremental, watermark=123)",
            syncContactsPhaseStartLine(true, "123"),
        )
        assertEquals(
            "[Sync] Phase start: contacts (FULL sync, reason=no watermark) — " +
                "local contacts not on device will be pruned",
            syncContactsPhaseStartLine(false, "no watermark"),
        )
        assertEquals("[Sync] Phase end: contacts - 55 (incremental)", syncContactsPhaseEndLine(55, "incremental"))
        assertEquals(
            "[Sync] Phase end: channels - 4 synced (device capacity: 8)",
            syncChannelsPhaseEndLine(4, 8),
        )
        assertEquals("[Sync] Phase end: messages - 12 polled", syncMessagesPhaseEndLine(12))
        assertEquals(
            "[Sync] Complete: platform=esp32, messages=12, duration=3.5s",
            syncCompleteLine("esp32", 12, 3.5),
        )
        assertEquals(
            "[Sync] Starting event monitoring for device A0000000",
            syncStartingEventMonitoringLine(radioId),
        )
        assertEquals(
            "[Sync] Starting auto-fetch for device A0000000",
            syncStartingAutoFetchLine(radioId),
        )
        assertEquals(
            "Connection setup complete for device A0000000-0000-0000-0000-000000000001",
            connectionSetupCompleteLine(radioId),
        )
        assertEquals("[Sync] Calling onSyncActivityStarted", SYNC_CALLING_ACTIVITY_STARTED)
        assertEquals(
            "[Sync] Calling onSyncActivityEnded (succeeded: true)",
            syncCallingActivityEndedLine(true),
        )
        assertEquals(
            "[Sync] Calling onSyncActivityEnded (succeeded: false)",
            syncCallingActivityEndedLine(false),
        )
    }
}
