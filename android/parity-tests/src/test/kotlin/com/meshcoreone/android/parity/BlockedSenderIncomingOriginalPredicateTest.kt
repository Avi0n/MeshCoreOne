// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedSenderMessageDeletionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BlockedSenderIncomingOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val at = Instant.ofEpochSecond(1_704_067_200)

    @Test fun incomingSenderDeletionRemovesBothOriginalChannelsAndKeepsLegit() = runTest {
        val spam1 = message("Spammer", channelIndex = 0u)
        val spam2 = message("Spammer", channelIndex = 1u)
        val legit = message("Legit", channelIndex = 0u)
        saveAndRequireIncoming(spam1, spam2, legit)

        store.deleteChannelMessages("Spammer", radio)

        assertNull(store.fetchMessage(key(spam1)))
        assertNull(store.fetchMessage(key(spam2)))
        requireOriginal(legit)
    }

    @Test fun incomingSameSenderDirectMessageSurvivesChannelDeletion() = runTest {
        val channel = message("Spammer")
        val direct = message("Spammer", channelIndex = null, contactID = UUID.randomUUID(), text = "dm")
        saveAndRequireIncoming(channel, direct)

        store.deleteChannelMessages("Spammer", radio)

        assertNull(store.fetchMessage(key(channel)))
        requireOriginal(direct)
    }

    @Test fun incomingOtherRadioMessageSurvivesSenderDeletion() = runTest {
        val selected = message("Spammer")
        val other = message("Spammer", radioId = RadioId(UUID.randomUUID()))
        saveAndRequireIncoming(selected, other)

        store.deleteChannelMessages("Spammer", radio)

        assertNull(store.fetchMessage(key(selected)))
        requireOriginal(other)
    }

    @Test fun incomingLegitMessageSurvivesMissingSenderDeletion() = runTest {
        val legit = message("Legit")
        saveAndRequireIncoming(legit)

        store.deleteChannelMessages("Ghost", radio)

        requireOriginal(legit)
    }

    @Test fun incomingSenderDeletionCascadesOnlyOriginalTargetReaction() = runTest {
        val spam = message("Spammer")
        val legit = message("Legit")
        saveAndRequireIncoming(spam, legit)
        val spamReaction = ReactionDTO(
            messageID = spam.id, emoji = "\uD83D\uDC4D", senderName = "Reactor",
            messageHash = "AABB1122", rawText = ":thumbsup:", receivedAt = at,
            channelIndex = 0u, radioId = radio,
        )
        val legitReaction = ReactionDTO(
            messageID = legit.id, emoji = "\u2764\uFE0F", senderName = "Reactor",
            messageHash = "CCDD3344", rawText = ":heart:", receivedAt = at,
            channelIndex = 0u, radioId = radio,
        )
        store.saveReaction(spamReaction)
        store.saveReaction(legitReaction)
        assertEquals(listOf(spamReaction), store.fetchReactions(key(spam)).toList())
        assertEquals(listOf(legitReaction), store.fetchReactions(key(legit)).toList())

        store.deleteChannelMessages("Spammer", radio)

        assertTrue(store.fetchReactions(key(spam)).isEmpty())
        assertEquals(1, store.fetchReactions(key(legit)).size)
        assertEquals(listOf(legitReaction), store.fetchReactions(key(legit)).toList())
        assertNull(store.fetchMessage(key(spam)))
        requireOriginal(legit)
    }

    @Test fun incomingNilSenderSystemMessageSurvivesNamedSenderDeletion() = runTest {
        val system = message(null, text = "system message")
        saveAndRequireIncoming(system)

        store.deleteChannelMessages("AnyName", radio)

        requireOriginal(system)
    }

    private fun message(
        senderName: String?,
        radioId: RadioId = radio,
        channelIndex: UByte? = 0u,
        contactID: UUID? = null,
        text: String = "test",
    ) = MessageDTO(
        id = UUID.randomUUID(),
        radioId = radioId,
        contactID = contactID,
        channelIndex = channelIndex,
        text = text,
        timestamp = 1_704_067_200u,
        createdAt = at,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        textType = TextType.PLAIN,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        senderKeyPrefix = null,
        senderNodeName = senderName,
        isRead = false,
        replyToID = null,
        roundTripTime = null,
        heardRepeats = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
    )

    private fun key(message: MessageDTO) = EntityKey(message.radioId, message.id)

    private suspend fun saveAndRequireIncoming(vararg messages: MessageDTO) {
        for (message in messages) store.saveMessage(message)
        for (message in messages) requireOriginal(message)
    }

    private suspend fun requireOriginal(expected: MessageDTO) {
        val actual = requireNotNull(store.fetchMessage(key(expected)))
        assertEquals(expected.id, actual.id)
        assertEquals(expected.radioId, actual.radioId)
        assertEquals(expected.contactID, actual.contactID)
        assertEquals(expected.channelIndex, actual.channelIndex)
        assertEquals(expected.senderNodeName, actual.senderNodeName)
        assertEquals(expected.text, actual.text)
        assertEquals(0L, actual.direction.rawValue)
        assertEquals(3L, actual.status.rawValue)
        assertEquals(TextType.PLAIN, actual.textType)
        assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(at, actual.createdAt)
        assertNull(actual.ackCode)
        assertEquals(0.toUByte(), actual.pathLength)
        assertNull(actual.snr)
        assertNull(actual.senderKeyPrefix)
        assertFalse(actual.isRead)
        assertNull(actual.replyToID)
        assertNull(actual.roundTripTime)
        assertEquals(0L, actual.heardRepeats)
        assertEquals(0L, actual.retryAttempt)
        assertEquals(0L, actual.maxRetryAttempts)
    }
}
