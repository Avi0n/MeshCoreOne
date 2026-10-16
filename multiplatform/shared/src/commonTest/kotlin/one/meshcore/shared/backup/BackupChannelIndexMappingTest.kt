package one.meshcore.shared.backup

import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupChannelIndexMappingTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun dropsChildrenOfDroppedChannelSlots() {
        val kept = sampleMessage(id = 1, channelIndex = 2, dedupIndex = 2)
        val dropped = sampleMessage(id = 2, channelIndex = 5, dedupIndex = 5)
        val reactionKept = sampleReaction(id = 1, messageId = kept.id, channelIndex = 2)
        val reactionDropped = sampleReaction(id = 2, messageId = dropped.id, channelIndex = 5)

        val result =
            BackupChannelIndexMapping.apply(
                messages = listOf(kept, dropped),
                reactions = listOf(reactionKept, reactionDropped),
                channelIndexRemap = emptyMap(),
                droppedChannelIndices = mapOf(radio to setOf(5)),
            )

        assertEquals(1, result.messagesDropped)
        assertEquals(1, result.reactionsDropped)
        assertEquals(listOf(kept.id), result.messages.map { it.id })
        assertEquals(listOf(reactionKept.id), result.reactions.map { it.id })
    }

    @Test
    fun remapsChannelIndexAndDedupKeyTogether() {
        val message =
            sampleMessage(
                id = 3,
                channelIndex = 5,
                dedupIndex = 5,
            )
        val reaction = sampleReaction(id = 3, messageId = message.id, channelIndex = 5)

        val result =
            BackupChannelIndexMapping.apply(
                messages = listOf(message),
                reactions = listOf(reaction),
                channelIndexRemap = mapOf(radio to mapOf(5 to 2)),
                droppedChannelIndices = emptyMap(),
            )

        assertEquals(0, result.messagesDropped)
        assertEquals(2, result.messages.single().channelIndex)
        assertEquals("ch-2-10-n-abcd", result.messages.single().deduplicationKey)
        assertEquals(2, result.reactions.single().channelIndex)
    }

    @Test
    fun leavesDirectMessagesAndNoopMapsUntouched() {
        val dm = sampleMessage(id = 4, channelIndex = null, dedupIndex = 0).copy(
            deduplicationKey = "dm-ignore",
        )
        val result =
            BackupChannelIndexMapping.apply(
                messages = listOf(dm),
                reactions = emptyList(),
                channelIndexRemap = emptyMap(),
                droppedChannelIndices = emptyMap(),
            )
        assertEquals(0, result.messagesDropped)
        assertNull(result.messages.single().channelIndex)
        assertEquals("dm-ignore", result.messages.single().deduplicationKey)
        assertTrue(result.reactions.isEmpty())
    }

    private fun sampleMessage(id: Int, channelIndex: Int?, dedupIndex: Int): MessageDto = MessageDto(
        id = Uuid.normalized("M0000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
        radioID = radio,
        text = "hi",
        timestamp = 10,
        createdAt = 10.0,
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
        channelIndex = channelIndex,
        deduplicationKey =
        if (channelIndex != null) {
            "${DeduplicationKey.CHANNEL_PREFIX}$dedupIndex-10-n-abcd"
        } else {
            null
        },
    )

    private fun sampleReaction(id: Int, messageId: Uuid, channelIndex: Int): ReactionDto = ReactionDto(
        id = Uuid.normalized("R0000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
        messageID = messageId,
        emoji = "👍",
        senderName = "n",
        messageHash = "abcd1234",
        rawText = "👍",
        receivedAt = 1.0,
        radioID = radio,
        channelIndex = channelIndex,
    )
}
