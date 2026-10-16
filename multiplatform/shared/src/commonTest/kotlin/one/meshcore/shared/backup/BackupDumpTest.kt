package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupDumpTest {
    @Test
    fun exportLineMatchesIos() {
        assertEquals(
            "Backup exported: 1200 bytes JSON → 400 bytes compressed",
            backupExportedLine(jsonBytes = 1200, compressedBytes = 400),
        )
    }

    @Test
    fun importCompleteLineMatchesIos() {
        assertEquals(
            "Import complete: 3 inserted, 1 merged, 2 skipped",
            backupImportCompleteLine(inserted = 3, merged = 1, skipped = 2),
        )
    }

    @Test
    fun duplicateDeviceLineMatchesIos() {
        assertEquals(
            "Backup contained 2 duplicate device public key(s); first occurrence wins.",
            backupDuplicateDeviceLine(2),
        )
    }

    @Test
    fun channelSlotDropLineMatchesIos() {
        val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        assertEquals(
            "Backup channel at slot 3 for radio ${radio.value} has no free local slot; " +
                "dropping it and its messages.",
            backupChannelSlotDropLine(slot = 3, radioID = radio),
        )
    }

    @Test
    fun totalSkippedIncludesDuplicateDevices() {
        val result = BackupImportResult(messagesSkipped = 1, devicesSkipped = 2, discoveredNodesSkipped = 3)
        assertEquals(6, result.totalSkipped)
    }

    @Test
    fun totalSkippedIncludesEveryFamily() {
        val result = BackupImportResult(
            messagesSkipped = 1,
            contactsSkipped = 1,
            channelsSkipped = 1,
            devicesSkipped = 1,
            roomMessagesSkipped = 1,
            reactionsSkipped = 1,
            messageRepeatsSkipped = 1,
            savedTracePathsSkipped = 1,
            sessionsSkipped = 1,
            blockedChannelSendersSkipped = 1,
            nodeStatusSnapshotsSkipped = 1,
            discoveredNodesSkipped = 1,
        )
        assertEquals(12, result.totalSkipped)
    }
}
