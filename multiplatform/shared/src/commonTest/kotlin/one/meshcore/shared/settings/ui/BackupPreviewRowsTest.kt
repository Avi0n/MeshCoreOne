package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import preview is what a user reads before deciding to restore. Listing only some of the kinds the
 * file will write understates the import, so every count the envelope carries has to reach a row.
 */
class BackupPreviewRowsTest {
    @Test
    fun everyKindTheEnvelopeCarriesGetsARow() {
        val rows = backupPreviewRows(preview())

        assertEquals(
            KINDS_CARRIED_BY_THE_PREVIEW,
            rows.size,
            "a kind the envelope counts is missing from the preview, so the import looks smaller than it is",
        )
    }

    @Test
    fun everyRowShowsItsOwnCount() {
        val rows = backupPreviewRows(preview())

        // Distinct counts in, distinct counts out: a row wired to the wrong field is otherwise invisible.
        assertEquals(rows.size, rows.map { it.count }.toSet().size)
    }

    /** iOS omits a kind the envelope has none of rather than printing a zero. */
    @Test
    fun aKindWithNothingInItIsOmitted() {
        val rows = backupPreviewRows(preview().copy(roomMessageCount = 0, savedTracePathCount = 0))

        assertEquals(KINDS_CARRIED_BY_THE_PREVIEW - 2, rows.size)
        assertTrue(rows.none { it.count == 0 })
    }

    @Test
    fun anEmptyEnvelopeListsNothing() {
        assertTrue(backupPreviewRows(empty()).isEmpty())
    }

    @Test
    fun importSuccessListsEveryInsertedFamily() {
        val rows = backupImportInsertedRows(
            BackupImportResult(
                devicesInserted = 1,
                contactsInserted = 2,
                channelsInserted = 3,
                sessionsInserted = 4,
                messagesInserted = 5,
                reactionsInserted = 6,
                messageRepeatsInserted = 7,
                roomMessagesInserted = 8,
                savedTracePathsInserted = 9,
                blockedChannelSendersInserted = 10,
                nodeStatusSnapshotsInserted = 11,
                discoveredNodesInserted = 12,
            ),
        )
        assertEquals(KINDS_CARRIED_BY_THE_PREVIEW, rows.size)
        assertEquals(rows.size, rows.map { it.count }.toSet().size)
    }

    @Test
    fun importSuccessOmitsAFamilyWithNoInserts() {
        val rows = backupImportInsertedRows(BackupImportResult(messagesInserted = 3))
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().count)
    }

    @Test
    fun alreadyHereListsEverySkippedFamilyInBackupModelOrder() {
        val rows = backupImportAlreadyHereRows(
            BackupImportResult(
                messagesSkipped = 1,
                contactsSkipped = 2,
                channelsSkipped = 3,
                devicesSkipped = 4,
                roomMessagesSkipped = 5,
                reactionsSkipped = 6,
                messageRepeatsSkipped = 7,
                savedTracePathsSkipped = 8,
                sessionsSkipped = 9,
                blockedChannelSendersSkipped = 10,
                nodeStatusSnapshotsSkipped = 11,
                discoveredNodesSkipped = 12,
            ),
        )
        assertEquals(KINDS_CARRIED_BY_THE_PREVIEW, rows.size)
        assertEquals(rows.size, rows.map { it.count }.toSet().size)
        assertEquals(1, rows.first().count)
        assertEquals(12, rows.last().count)
    }

    @Test
    fun alreadyHereListsSkippedMessagesAndOmitsMerges() {
        val rows = backupImportAlreadyHereRows(
            BackupImportResult(messagesSkipped = 4, contactsMerged = 2, devicesMerged = 1),
        )
        assertEquals(1, rows.size)
        assertEquals(4, rows.single().count)
    }

    @Test
    fun alreadyHereListsDuplicateDeviceSkips() {
        val rows = backupImportAlreadyHereRows(BackupImportResult(devicesSkipped = 2))
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().count)
    }

    @Test
    fun alreadyHereListsSkippedKindsInBackupModelOrder() {
        val rows = backupImportAlreadyHereRows(
            BackupImportResult(messagesSkipped = 1, devicesSkipped = 2, discoveredNodesSkipped = 3),
        )
        assertEquals(3, rows.size)
        assertEquals(1, rows[0].count)
        assertEquals(2, rows[1].count)
        assertEquals(3, rows[2].count)
    }

    @Test
    fun droppedListsKindsInBackupModelOrder() {
        val rows = backupImportDroppedRows(
            BackupImportResult(
                messagesDropped = 1,
                channelsDropped = 2,
                reactionsDropped = 3,
                discoveredNodesDropped = 4,
            ),
        )
        assertEquals(4, rows.size)
        assertEquals(1, rows[0].count)
        assertEquals(2, rows[1].count)
        assertEquals(3, rows[2].count)
        assertEquals(4, rows[3].count)
    }

    @Test
    fun droppedOmitsZeroFamilies() {
        val rows = backupImportDroppedRows(BackupImportResult(channelsDropped = 2, messagesDropped = 5))
        assertEquals(2, rows.size)
        assertEquals(5, rows[0].count)
        assertEquals(2, rows[1].count)
    }

    @Test
    fun alreadyHereOmitsAMergeOnlyRestore() {
        assertTrue(
            backupImportAlreadyHereRows(
                BackupImportResult(contactsMerged = 2, channelsMerged = 1, sessionsMerged = 1),
            ).isEmpty(),
        )
    }

    @Test
    fun prefsOnlyRestoreCountsAsRestoredChanges() {
        assertTrue(BackupImportResult(userDefaultsRestored = true).hasRestoredChanges)
        assertTrue(!BackupImportResult().hasRestoredChanges)
    }

    private fun preview() = BackupImportPreview(
        version = 1,
        exportDateEpochSeconds = 0.0,
        appVersion = "1",
        appBuild = "1",
        deviceCount = 1,
        contactCount = 2,
        channelCount = 3,
        messageCount = 4,
        messageRepeatCount = 5,
        reactionCount = 6,
        roomMessageCount = 7,
        remoteNodeSessionCount = 8,
        savedTracePathCount = 9,
        blockedChannelSenderCount = 10,
        nodeStatusSnapshotCount = 11,
        discoveredNodeCount = 12,
        manifestValid = true,
    )

    private fun empty() = preview().copy(
        deviceCount = 0,
        contactCount = 0,
        channelCount = 0,
        messageCount = 0,
        messageRepeatCount = 0,
        reactionCount = 0,
        roomMessageCount = 0,
        remoteNodeSessionCount = 0,
        savedTracePathCount = 0,
        blockedChannelSenderCount = 0,
        nodeStatusSnapshotCount = 0,
        discoveredNodeCount = 0,
    )

    private companion object {
        /** Every count field on `BackupImportPreview`, matching the iOS `BackupModelKind` cases. */
        const val KINDS_CARRIED_BY_THE_PREVIEW = 12
    }
}
