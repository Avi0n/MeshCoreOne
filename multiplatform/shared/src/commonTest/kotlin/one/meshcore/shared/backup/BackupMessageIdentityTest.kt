package one.meshcore.shared.backup

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupMessageIdentityTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val contact = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
    private val sharedId = Uuid.normalized("M0000000-0000-0000-0000-0000000000AA")

    @Test
    fun duplicateIdsInOneEnvelopeKeepTheFirstRow() {
        val first = sampleDirect(sharedId, text = "First body", dedup = "shared-id-first")
        val second = sampleDirect(sharedId, text = "Second body", dedup = "shared-id-second")

        val plan = BackupMessageIdentity.plan(listOf(first, second), local = emptyList())

        assertEquals(listOf(first.id), plan.toInsert.map { it.id })
        assertEquals("First body", plan.toInsert.single().text)
        assertEquals(1, plan.skipped)
        assertTrue(plan.messageIDByBackupID.isEmpty())
    }

    @Test
    fun aLocalRowWithTheSameIdIsNotOverwritten() {
        val local = sampleDirect(sharedId, text = "Local body", dedup = "local-key")
        val backup = sampleDirect(sharedId, text = "Backup body", dedup = "backup-key")

        val plan = BackupMessageIdentity.plan(listOf(backup), local = listOf(local))

        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.skipped)
        assertTrue(plan.messageIDByBackupID.isEmpty())
    }

    @Test
    fun sameBackupKeyStillRemapsOntoTheLocalWinner() {
        val local = sampleDirect(Uuid.normalized("M0000000-0000-0000-0000-0000000000L1"), dedup = "same-key")
        val backup = sampleDirect(Uuid.normalized("M0000000-0000-0000-0000-0000000000B1"), dedup = "same-key")

        val plan = BackupMessageIdentity.plan(listOf(backup), local = listOf(local))

        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.skipped)
        assertEquals(local.id, plan.messageIDByBackupID[backup.id])
    }

    @Test
    fun skippedIncomingParentRetainsItsPathForArrivalMerge() {
        val local = sampleDirect(Uuid.normalized("M0000000-0000-0000-0000-0000000000L1"), dedup = "same-key")
        val backup = sampleDirect(Uuid.normalized("M0000000-0000-0000-0000-0000000000B1"), dedup = "same-key")
            .copy(pathNodes = byteArrayOf(1, 2), pathLength = 2)

        val plan = BackupMessageIdentity.plan(listOf(backup), local = listOf(local))

        assertEquals(local.id, plan.skippedIncomingParents.single().localMessageID)
        assertTrue(plan.skippedIncomingParents.single().dto.pathNodes!!.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun incomingPathMergeAdoptsUnknownParentAndSkipsCanonicalRepeat() {
        val local = sampleDirect(sharedId, dedup = "same-key")
        val foreign = local.copy(pathNodes = byteArrayOf(1), pathLength = 1)
        val repeated = MessageRepeatDto(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000010"),
            messageID = sharedId,
            receivedAt = 2.0,
            pathNodes = byteArrayOf(1),
        )

        val result = BackupIncomingPathMerge.plan(
            parent = local,
            skippedParents = listOf(foreign),
            existingRepeats = emptyList(),
            incomingRepeats = listOf(repeated),
        )

        assertTrue(result.parent.pathNodes!!.contentEquals(byteArrayOf(1)))
        assertEquals(1, result.parent.pathLength)
        assertTrue(result.promotedRepeats.isEmpty())
        assertTrue(result.repeatsToImport.isEmpty())
        assertEquals(1, result.skippedRepeats)
    }

    @Test
    fun incomingPathMergePromotesOneDistinctPathAndDeduplicatesReimport() {
        val local = sampleDirect(sharedId, dedup = "same-key")
            .copy(pathNodes = byteArrayOf(1), pathLength = 1)
        val foreign = local.copy(pathNodes = byteArrayOf(2), pathLength = 2, createdAt = 3.0)
        val samePathRepeat = MessageRepeatDto(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000011"),
            messageID = sharedId,
            receivedAt = 4.0,
            pathNodes = byteArrayOf(2),
        )

        val first = BackupIncomingPathMerge.plan(local, listOf(foreign), emptyList(), listOf(samePathRepeat))
        assertEquals(1, first.promotedRepeats.size)
        assertEquals(3.0, first.promotedRepeats.single().receivedAt)
        assertTrue(first.promotedRepeats.single().pathNodes.contentEquals(byteArrayOf(2)))
        assertTrue(first.repeatsToImport.isEmpty())
        assertEquals(1, first.skippedRepeats)

        val second = BackupIncomingPathMerge.plan(local, listOf(foreign), first.promotedRepeats, listOf(samePathRepeat))
        assertTrue(second.promotedRepeats.isEmpty())
        assertTrue(second.repeatsToImport.isEmpty())
        assertEquals(1, second.skippedRepeats)
    }

    @Test
    fun outgoingIdenticalPathRepeatsRemainSeparate() {
        val outgoing = sampleDirect(sharedId, dedup = "same-key")
            .copy(direction = MessageDirection.OUTGOING, pathNodes = byteArrayOf(1))
        val repeated = MessageRepeatDto(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000012"),
            messageID = sharedId,
            receivedAt = 2.0,
            pathNodes = byteArrayOf(1),
        )

        val result = BackupIncomingPathMerge.plan(outgoing, emptyList(), emptyList(), listOf(repeated))

        assertEquals(listOf(repeated), result.repeatsToImport)
        assertEquals(0, result.skippedRepeats)
    }

    private fun sampleDirect(id: Uuid, text: String = "hello", dedup: String): MessageDto = MessageDto(
        id = id,
        radioID = radio,
        text = text,
        timestamp = 1,
        createdAt = 1.0,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.SENT,
        textType = TextType.PLAIN,
        pathLength = 0,
        isRead = true,
        heardRepeats = 0,
        sendCount = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = false,
        contactID = contact,
        deduplicationKey = dedup,
    )
}
