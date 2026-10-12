// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceUnreadAndStatusOriginalPredicateTest : RoomProcessTest() {
    private suspend fun device(): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        ).also { store.saveDevice(it) }
    }

    private fun frame(name: String = "TestContact", keyByte: Int = 0x55) = ContactFrame(
        Bytes(ByteArray(32) { keyByte.toByte() }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
        Instant.now().epochSecond.toUInt(), 37.7749, -122.4194, Instant.now().epochSecond.toUInt(),
    )

    private fun direct(status: MessageStatus, ackCode: UInt? = null) = MessageDTO(
        radioId = RadioId(UUID.randomUUID()), contactID = UUID.randomUUID(), text = "Test message",
        timestamp = Instant.now().epochSecond.toUInt(), status = status, ackCode = ackCode,
    )

    @OriginalCase("PersistenceStoreTests::fetchMessage by dedup key returns the row for the same radio()")
    @Test fun originalDedupLookupRetainsIncomingSentAliceHelloAndIndependentRadioAbsence() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val otherRadio = RadioId(UUID.randomUUID())
        val key = "ch-0-1-Alice-ABCD"
        val message = MessageDTO(
            radioId = radio, channelIndex = 0u, text = "hello",
            timestamp = Instant.now().epochSecond.toUInt(), direction = MessageDirection.INCOMING,
            status = MessageStatus.SENT, senderNodeName = "Alice", deduplicationKey = key,
        )
        store.saveMessage(message)

        assertEquals(message.id, store.fetchMessage(key, radio)?.id)
        assertNull(store.fetchMessage(key, otherRadio))
    }

    @OriginalCase("PersistenceStoreTests::Save and fetch messages for contact()")
    @Test fun originalFiveMessageHistoryRetainsCurrentTimestampsCountAndBothEndpoints() = runTest {
        val device = device()
        val contact = store.saveContact(device.radioId, frame())
        for (i in 0..<5) {
            store.saveMessage(MessageDTO(
                radioId = device.radioId, contactID = contact.id, text = "Message $i",
                timestamp = Instant.now().epochSecond.toUInt() + i.toUInt(),
            ))
        }
        val messages = store.fetchMessages(EntityKey(device.radioId, contact.id))

        assertEquals(5, messages.size)
        assertEquals("Message 0", messages.firstOrNull()?.text)
        assertEquals("Message 4", messages.lastOrNull()?.text)
    }

    @OriginalCase("PersistenceStoreTests::Update message status()")
    @Test fun originalPendingSendingSentTransitionsRetainBothIndependentStoredReads() = runTest {
        val device = device()
        val contact = store.saveContact(device.radioId, frame())
        val createdAt = Instant.now()
        val message = MessageDTO(
            radioId = device.radioId, contactID = contact.id, text = "Test", status = MessageStatus.PENDING,
            timestamp = createdAt.epochSecond.toUInt(), createdAt = createdAt,
        )
        store.saveMessage(message)
        val key = EntityKey(device.radioId, message.id)
        store.updateMessageStatus(key, MessageStatus.SENDING)
        assertEquals(MessageStatus.SENDING, store.fetchMessage(key)?.status)
        store.updateMessageStatus(key, MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::Save and fetch channels()")
    @Test fun originalPublicAndPrivateChannelsRetainBothSecretsAndAllFiveOrderedPredicates() = runTest {
        val device = device()
        store.saveChannel(device.radioId, ChannelInfo(0u, "Public", Bytes(ByteArray(16))))
        store.saveChannel(device.radioId, ChannelInfo(1u, "Private", Bytes(ByteArray(16) { 0x42 })))
        val channels = store.fetchChannels(device.radioId)

        assertEquals(2, channels.size)
        assertEquals(0.toUByte(), channels[0].index)
        assertEquals("Public", channels[0].name)
        assertEquals(1.toUByte(), channels[1].index)
        assertEquals("Private", channels[1].name)
    }

    @OriginalCase("PersistenceStoreTests::Get total unread counts()")
    @Test fun originalSeparateTwoOneAndThreeIncrementsRetainAllThreeUnreadTotals() = runTest {
        val device = device()
        val first = store.saveContact(device.radioId, frame("Contact1"))
        store.incrementUnreadCount(EntityKey(device.radioId, first.id))
        store.incrementUnreadCount(EntityKey(device.radioId, first.id))
        val second = store.saveContact(device.radioId, frame("Contact2", 0x56))
        store.incrementUnreadCount(EntityKey(device.radioId, second.id))
        val channel = store.saveChannel(device.radioId, ChannelInfo(0u, "Public", Bytes(ByteArray(16))))
        store.incrementChannelUnreadCount(EntityKey(device.radioId, channel))
        store.incrementChannelUnreadCount(EntityKey(device.radioId, channel))
        store.incrementChannelUnreadCount(EntityKey(device.radioId, channel))
        val counts = store.getTotalUnreadCounts(device.radioId)

        assertEquals(3L, counts.contacts)
        assertEquals(3L, counts.channels)
        assertEquals(0L, counts.rooms)
    }

    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes blocked contacts()")
    @Test fun originalBlockedFiveDoesNotContributeToTheSeparateRegularTwo() = runTest {
        val device = device()
        val regular = store.saveContact(device.radioId, frame("RegularContact"))
        store.incrementUnreadCount(EntityKey(device.radioId, regular.id))
        store.incrementUnreadCount(EntityKey(device.radioId, regular.id))
        store.saveContact(ContactDTO(
            radioId = device.radioId, publicKey = Bytes(ByteArray(32) { 0x56 }),
            name = "BlockedContact", typeRawValue = 0u, flags = 0u, outPathLength = 0u,
            outPath = Bytes.EMPTY, lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
            lastModified = 0u, lastHeardTimestamp = null, nickname = null, isBlocked = true,
            isMuted = false, isFavorite = false, lastMessageDate = null, unreadCount = 5,
        ))

        assertEquals(2L, store.getTotalUnreadCounts(device.radioId).contacts)
    }

    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes repeater contacts()")
    @Test fun originalRepeaterThreeDoesNotContributeToTheSeparateChatTwo() = runTest {
        val device = device()
        val chat = store.saveContact(device.radioId, frame("ChatContact"))
        store.incrementUnreadCount(EntityKey(device.radioId, chat.id))
        store.incrementUnreadCount(EntityKey(device.radioId, chat.id))
        val repeater = store.saveContact(device.radioId, ContactFrame(
            Bytes(ByteArray(32) { 0x56 }), ContactType.REPEATER, 0u, 2u, Bytes.of(1, 2),
            "RepeaterContact", Instant.now().epochSecond.toUInt(), 0.0, 0.0, Instant.now().epochSecond.toUInt(),
        ))
        store.incrementUnreadCount(EntityKey(device.radioId, repeater.id))
        store.incrementUnreadCount(EntityKey(device.radioId, repeater.id))
        store.incrementUnreadCount(EntityKey(device.radioId, repeater.id))

        assertEquals(2L, store.getTotalUnreadCounts(device.radioId).contacts)
    }

    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes repeater-role sessions()")
    @Test fun originalRepeaterSessionThreeDoesNotContributeToTheSeparateRoomTwo() = runTest {
        val device = device()
        val room = RemoteNodeSessionDTO(
            radioId = device.radioId, publicKey = Bytes(ByteArray(32) { 0x66 }),
            name = "TestRoom", role = RemoteNodeRole.ROOM_SERVER, latitude = 37.7749, longitude = -122.4194,
        )
        store.saveRemoteNodeSessionDTO(room)
        store.incrementRoomUnreadCount(EntityKey(device.radioId, room.id))
        store.incrementRoomUnreadCount(EntityKey(device.radioId, room.id))
        val repeater = RemoteNodeSessionDTO(
            radioId = device.radioId, publicKey = Bytes(ByteArray(32) { 0x67 }),
            name = "RepeaterAdmin", role = RemoteNodeRole.REPEATER,
        )
        store.saveRemoteNodeSessionDTO(repeater)
        store.incrementRoomUnreadCount(EntityKey(device.radioId, repeater.id))
        store.incrementRoomUnreadCount(EntityKey(device.radioId, repeater.id))
        store.incrementRoomUnreadCount(EntityKey(device.radioId, repeater.id))

        assertEquals(2L, store.getTotalUnreadCounts(device.radioId).rooms)
    }

    @OriginalCase("PersistenceStoreTests::updateMessageAck refuses to write .delivered onto a .failed row()")
    @Test fun originalFailedDirectRowRejectsTheIndependentDeadBeefDeliveryWrite() = runTest {
        val message = direct(MessageStatus.FAILED)
        store.saveMessage(message)
        val key = EntityKey(message.radioId, message.id)
        store.updateMessageAck(key, 0xDEAD_BEEFu, MessageStatus.DELIVERED)

        assertEquals(MessageStatus.FAILED, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::clearRetryingToSent no-ops on .failed but promotes .retrying and .pending to .sent()")
    @Test fun originalFailedRetryingPendingContextsRetainAllThreeReturnsAndAllThreeStatuses() = runTest {
        val failed = direct(MessageStatus.FAILED)
        store.saveMessage(failed)
        val failedKey = EntityKey(failed.radioId, failed.id)
        assertFalse(store.clearRetryingToSent(failedKey))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(failedKey)?.status)
        val retrying = direct(MessageStatus.RETRYING)
        store.saveMessage(retrying)
        val retryingKey = EntityKey(retrying.radioId, retrying.id)
        assertTrue(store.clearRetryingToSent(retryingKey))
        assertEquals(MessageStatus.SENT, store.fetchMessage(retryingKey)?.status)
        val pending = direct(MessageStatus.PENDING)
        store.saveMessage(pending)
        val pendingKey = EntityKey(pending.radioId, pending.id)
        assertTrue(store.clearRetryingToSent(pendingKey))
        assertEquals(MessageStatus.SENT, store.fetchMessage(pendingKey)?.status)
    }

    @OriginalCase("PersistenceStoreTests::clearRetryingToSent no-ops on a .delivered row()")
    @Test fun originalSeparateDeliveredContextRetainsFalseAndDeliveredReadback() = runTest {
        val message = direct(MessageStatus.DELIVERED)
        store.saveMessage(message)
        val key = EntityKey(message.radioId, message.id)

        assertFalse(store.clearRetryingToSent(key))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::hasOutgoingSentDM flags a stuck .sent DM by ackCode and ignores other rows()")
    @Test fun originalSentAckDiagnosticRetainsCafeF00dOneAndIndependentDeliveredBadc0de5() = runTest {
        val sent = direct(MessageStatus.SENT, 0xCAFE_F00Du)
        store.saveMessage(sent)
        assertTrue(store.hasOutgoingSentDM(sent.radioId, 0xCAFE_F00Du))
        assertFalse(store.hasOutgoingSentDM(sent.radioId, 1u))
        val delivered = direct(MessageStatus.DELIVERED, 0xBADC_0DE5u)
        store.saveMessage(delivered)

        assertFalse(store.hasOutgoingSentDM(delivered.radioId, 0xBADC_0DE5u))
    }
}
