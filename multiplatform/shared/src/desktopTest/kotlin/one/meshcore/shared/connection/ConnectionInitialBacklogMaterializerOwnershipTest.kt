package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.BODY
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.NOW
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.PUBLIC_A
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.oldFrame
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.persistence.direction
import one.meshcore.shared.persistence.status
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionInitialBacklogMaterializerOwnershipTest {
    @Test
    fun retiredCompletedMaterializerCannotReplayOldProtocolFields() = exercise {
        val old = start()
        val foreign = replace(DEVICE_B)
        settleCancelledLookup(old)
        assertTrue(first.closed && old.link.disconnected && old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertEquals(1, first.closeCount)
        assertNotEquals(old.container.radioId, foreign.radioId)
        rows.enrich(foreign.radioId, foreign.selfPublicKey)
        val foreignBefore = rows.snapshot(foreign.radioId)
        val fresh = replace(DEVICE_A)
        assertEquals(old.container.radioId, fresh.radioId)
        assertContentEquals(PUBLIC_A, fresh.selfPublicKey)
        rows.enrich(fresh.radioId, fresh.selfPublicKey)
        val before = contact(fresh.radioId)
        val beforeSnapshot = rows.snapshot(fresh.radioId)
        val historyBefore = database.messageDao().listByRadio(fresh.radioId).map { it.id }.toSet()
        val generation = manager.connectGeneration
        val savedPrefs = preferences()
        val savedWatermark = watermark(fresh.radioId)
        val oldResult = joinOld(old)
        assertCurrent(fresh, DEVICE_A, generation)
        assertEquals(savedPrefs, preferences())
        assertEquals(savedWatermark, watermark(fresh.radioId))
        assertEquals(foreignBefore, rows.snapshot(foreign.radioId))
        assertEquals(0, first.autoFetchStartCount)
        when (first.nextMessageClosedAtAdmission) {
            true -> {
                assertEquals(1, first.closedMessageRejections)
                assertEquals(1, first.getMessageCount)
            }
            false -> {
                assertEquals(0, first.closedMessageRejections)
                assertEquals(2, first.getMessageCount)
                assertEquals(
                    one.meshcore.shared.meshcore.GetMessageResult.NoMoreMessages,
                    first.nextMessageNormalResult,
                )
            }
            null -> assertTrue(oldResult is CancellationException && !first.completedReturnForwarded)
        }
        val messages = database.messageDao().listByRadio(fresh.radioId)
        assertTrue(messages.map { it.id }.toSet().containsAll(historyBefore))
        val received = messages.singleOrNull { it.text == BODY }
        val arrivedAfterSnapshot = received != null && received.id !in historyBefore
        assertEquals(historyBefore.size + if (arrivedAfterSnapshot) 1 else 0, messages.size)
        if (received != null) {
            assertIncoming(received, fresh.radioId, received.contactID)
            assertEquals(MessageDirection.INCOMING, received.direction)
            assertEquals(MessageStatus.DELIVERED, received.status)
            assertEquals(NOW, received.createdAt)
            assertEquals(NOW, received.sortDate)
            assertEquals(fresh.radioId, received.radioID)
            assertEquals(3, received.pathLength)
            assertEquals(-5.0, received.snr)
            assertEquals(NOW.toLong(), received.timestamp)
            assertContentEquals(oldFrame.publicKey.copyOf(PREFIX_BYTES), received.senderKeyPrefix)
            assertTrue(received.contactID == before.id || received.contactID == null)
            assertFalse(received.containsSelfMention)
        }
        val expected = if (arrivedAfterSnapshot && received?.contactID == before.id) {
            before.copy(
                lastMessageDate = NOW,
                lastHeardTimestamp = NOW.toLong(),
                unreadCount = before.unreadCount + 1,
            )
        } else {
            before
        }
        val afterSnapshot = rows.snapshot(fresh.radioId)
        assertEquals(beforeSnapshot.drop(HISTORY_DEPENDENTS_START), afterSnapshot.drop(HISTORY_DEPENDENTS_START))
        val retainedHistory = (afterSnapshot[MESSAGES_INDEX] as List<*>).filter { item ->
            ((item as List<*>).first() as one.meshcore.shared.persistence.MessageEntity).id in historyBefore
        }
        assertEquals(beforeSnapshot[MESSAGES_INDEX], retainedHistory)
        val otherBefore = (beforeSnapshot[CONTACTS_INDEX] as List<*>).filter { item ->
            ((item as List<*>).first() as one.meshcore.shared.persistence.ContactEntity).id != before.id
        }
        val otherAfter = (afterSnapshot[CONTACTS_INDEX] as List<*>).filter { item ->
            ((item as List<*>).first() as one.meshcore.shared.persistence.ContactEntity).id != before.id
        }
        assertEquals(otherBefore, otherAfter)
        noOutgoing()
        assertEquals(
            rows.contactSnapshot(expected),
            rows.contactSnapshot(contact(fresh.radioId)),
            "LAST: retired materializer replayed old protocol fields into fresh A",
        )
    }

    @Test
    fun liveCompletedMaterializerRetainsIncomingMessageAndPendingKey() = exercise {
        val old = start()
        joinOld(old)
        old.caller.await().getOrThrow()
        assertSame(old.container, manager.services)
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertTrue(liveRetentionQualified && liveEventsQualified)
        assertEquals(0, first.closedMessageRejections)
        assertEquals(1, first.autoFetchStartCount)
        assertFalse(first.closed || old.container.sendQueue.isRetired)
        val p = contact(old.container.radioId)
        val expected = one.meshcore.shared.persistence.ContactEntity(
            id = p.id, radioID = old.container.radioId, publicKey = oldFrame.publicKey,
            name = oldFrame.advertisedName, typeRawValue = oldFrame.typeRawValue, flags = oldFrame.flags,
            outPathLength = oldFrame.outPathLength, outPath = oldFrame.outPath,
            lastAdvertTimestamp = oldFrame.lastAdvertisement, latitude = oldFrame.latitude,
            longitude = oldFrame.longitude, lastModified = oldFrame.lastModified,
            lastHeardTimestamp = NOW.toLong(), isBlocked = false, isMuted = false,
            isFavorite = false, isPinned = false, unreadCount = 1, unreadMentionCount = 0,
            lastMessageDate = NOW,
        )
        assertEquals(rows.contactSnapshot(expected), rows.contactSnapshot(p))
        assertEquals(oldFrame.advertisedName, p.name)
        assertEquals(oldFrame.typeRawValue, p.typeRawValue)
        assertEquals(oldFrame.flags, p.flags)
        assertEquals(oldFrame.lastModified, p.lastModified)
        assertContentEquals(oldFrame.outPath, p.outPath)
        assertEquals(NOW.toLong(), p.lastHeardTimestamp)
        assertEquals(NOW, p.lastMessageDate)
        assertEquals(1, p.unreadCount)
        assertEquals(0, p.unreadMentionCount)
        val message = database.messageDao().listByRadio(old.container.radioId).single()
        assertIncoming(message, old.container.radioId, p.id)
        assertEquals(p.id, message.contactID)
        assertEquals(BODY, message.text)
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(MessageStatus.DELIVERED, message.status)
        assertContentEquals(oldFrame.publicKey.copyOf(PREFIX_BYTES), message.senderKeyPrefix)
        val discovered = database.discoveredNodeDao().listAll().single()
        val expectedDiscover = one.meshcore.shared.persistence.DiscoveredNodeEntity(
            id = discovered.id, radioID = old.container.radioId, publicKey = oldFrame.publicKey,
            name = oldFrame.advertisedName, typeRawValue = oldFrame.typeRawValue,
            lastHeardEpochSeconds = NOW.toLong(), lastAdvertTimestamp = oldFrame.lastAdvertisement,
            latitude = oldFrame.latitude, longitude = oldFrame.longitude,
            outPathLength = oldFrame.outPathLength, outPath = oldFrame.outPath,
        )
        assertEquals(expectedDiscover, discovered)
        assertContentEquals(oldFrame.publicKey, discovered.publicKey)
        assertEquals(old.container.radioId, discovered.radioID)
        assertEquals(NOW.toLong(), discovered.lastHeardEpochSeconds)
        noOutgoing()
    }

    private fun exercise(block: suspend ConnectionInitialBacklogMaterializerFixture.() -> Unit) {
        val fixture = ConnectionInitialBacklogMaterializerFixture()
        try {
            runBlocking(fixture.dispatcher) { withTimeout(TIMEOUT_MS) { fixture.block() } }
        } finally {
            fixture.close()
        }
    }

    private companion object {
        const val PREFIX_BYTES = 6
        const val CONTACTS_INDEX = 0
        const val MESSAGES_INDEX = 1
        const val HISTORY_DEPENDENTS_START = 2
    }
}
