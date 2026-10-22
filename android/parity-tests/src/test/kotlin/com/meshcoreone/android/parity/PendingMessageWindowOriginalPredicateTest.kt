// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreMessageWindowTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PendingMessageWindowOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val contact = EntityKey(radio, UUID.randomUUID())

    @Test fun pendingNilAnchorPreservesFullSourceFloorAndHasMore() = runTest {
        val original = persist((1u..10u).toList())

        val window = store.fetchMessageWindow(contact, anchorSortDate = null, floorLimit = 3)

        assertEquals(listOf(8u, 9u, 10u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
        requireOriginalWindow(window, original.takeLast(3))
    }

    @Test fun pendingAnchorWideningPreservesFullSourceRows() = runTest {
        val original = persist((1u..10u).toList())

        val window = store.fetchMessageWindow(contact, Instant.ofEpochSecond(4), floorLimit = 3)

        assertEquals(listOf(4u, 5u, 6u, 7u, 8u, 9u, 10u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
        requireOriginalWindow(window, original.drop(3))
    }

    @Test fun pendingAnchorIncludesBothDistinctOriginalTieRows() = runTest {
        val original = persist(listOf(3u, 5u, 5u, 7u))
        val anchor = Instant.ofEpochSecond(5)
        assertEquals(2, original.filter { it.timestamp == 5u }.map { it.id }.toSet().size)

        val window = store.fetchMessageWindow(contact, anchor, floorLimit = 1)

        assertEquals(2, window.messages.count { it.sortDate == anchor })
        assertFalse(window.messages.map { it.timestamp }.contains(3u))
        assertTrue(window.hasMore)
        requireOriginalWindow(window, original.drop(1))
    }

    @Test fun pendingProbeAndExactBoundaryPreserveSourceHasMore() = runTest {
        val original = persist((1u..4u).toList())

        val window = store.fetchMessageWindow(contact, anchorSortDate = null, floorLimit = 3)

        assertEquals(listOf(2u, 3u, 4u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
        requireOriginalWindow(window, original.drop(1))

        val exact = store.fetchMessageWindow(contact, anchorSortDate = null, floorLimit = 4)

        assertEquals(listOf(1u, 2u, 3u, 4u), exact.messages.map { it.timestamp })
        assertFalse(exact.hasMore)
        requireOriginalWindow(exact, original)
    }

    private suspend fun persist(timestamps: List<UInt>): List<MessageDTO> {
        val messages = timestamps.map { timestamp ->
            MessageDTO(
                id = UUID.randomUUID(), radioId = radio, contactID = contact.id, channelIndex = null,
                text = "m$timestamp", timestamp = timestamp, createdAt = Instant.ofEpochSecond(timestamp.toLong()),
                direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING, textType = TextType.PLAIN,
                ackCode = null, pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null,
                senderNodeName = null, isRead = false, replyToID = null, roundTripTime = null,
                heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, containsSelfMention = false,
            )
        }
        for (message in messages) {
            store.saveMessage(message)
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        }
        val actual = store.fetchMessages(contact, limit = timestamps.size.toLong())
        assertEquals(messages.size, actual.size)
        assertEquals(messages.toSet(), actual.toSet())
        return messages
    }

    private suspend fun requireOriginalWindow(window: MessageWindow, expected: List<MessageDTO>) {
        assertEquals(expected.size, window.messages.size)
        assertEquals(expected.map { it.timestamp }, window.messages.map { it.timestamp })
        assertEquals(expected.toSet(), window.messages.toSet())
        for (message in window.messages) {
            assertEquals(1L, message.direction.rawValue)
            assertEquals(0L, message.status.rawValue)
            assertEquals(contact.id, message.contactID)
            assertEquals(radio, message.radioId)
            assertNull(message.channelIndex)
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        }
    }
}
