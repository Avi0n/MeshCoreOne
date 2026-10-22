// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.BlockedChannelSenderDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceCascadeOriginalPredicateTest : RoomProcessTest() {
    private data class Seed(val message: EntityKey, val session: EntityKey)

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

    private suspend fun message(radio: RadioId, text: String): EntityKey {
        val contact = store.saveContact(radio, frame()).id
        val message = MessageDTO(
            radioId = radio, contactID = contact, text = text, timestamp = Instant.now().epochSecond.toUInt(),
        )
        store.saveMessage(message)
        return EntityKey(radio, message.id)
    }

    private suspend fun repeat(message: EntityKey) {
        store.saveMessageRepeat(
            message.radioId, MessageRepeatDTO(
                messageID = message.id, receivedAt = Instant.now(), pathNodes = Bytes.of(0x31),
                pathLength = 0u, snr = 8.5, rssi = -90, rxLogEntryID = null,
            ),
        )
    }

    private suspend fun seedAllEntityTypes(radio: RadioId): Seed {
        val message = message(radio, "Hello!")
        repeat(message)
        store.saveChannel(radio, ChannelInfo(1u, "Private", Bytes(ByteArray(16) { 0x42 })))
        val emoji = "\uD83D\uDC4D"
        store.saveReaction(
            ReactionDTO(
                messageID = message.id, emoji = emoji, senderName = "Reactor",
                messageHash = "AABBCCDD", rawText = emoji, radioId = radio,
            ),
        )
        val session = RemoteNodeSessionDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { 0x66 }), name = "TestRoom",
            role = RemoteNodeRole.ROOM_SERVER, latitude = 37.7749, longitude = -122.4194,
        )
        store.saveRemoteNodeSessionDTO(session)
        store.saveRoomMessage(
            radio, RoomMessageDTO(
                sessionID = session.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                authorName = "Author", text = "Room message", timestamp = Instant.now().epochSecond.toUInt(),
            ),
        )
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Spammer", radioId = radio))
        store.saveRxLogEntry(
            RxLogEntryDTO.fromParsed(
                radio, ParsedRxLogData(
                    snr = 10.5, rssi = -65, rawPayload = Bytes.of(0x15, 1, 2, 3),
                    routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT,
                    payloadVersion = 0u, payloadTypeBits = 5u, transportCode = null,
                    pathLength = 1u, pathNodes = listOf(0x42u), packetPayload = Bytes.of(0xAB, 0xCD, 0xEF),
                ),
                channelIndex = 1u, channelName = "TestChannel", decryptStatus = DecryptStatus.SUCCESS,
                senderTimestamp = 12345u, decodedText = "Hello mesh!",
            ),
        )
        store.upsertDiscoveredNode(radio, frame("Discovered", 0x56))
        return Seed(message, EntityKey(radio, session.id))
    }

    private suspend fun assertAllDataExists(radio: RadioId, seed: Seed) {
        assertEquals(1, store.fetchContacts(radio).size)
        assertEquals(1, store.fetchChannels(radio).size)
        assertEquals(1, store.fetchReactions(seed.message).size)
        assertEquals(1, store.fetchRemoteNodeSessions(radio).size)
        assertEquals(1, store.fetchRoomMessages(seed.session).size)
        assertEquals(1, store.fetchBlockedChannelSenders(radio).size)
        assertEquals(1, store.fetchRxLogEntries(radio).size)
        assertEquals(1, store.fetchDiscoveredNodes(radio).size)
    }

    private suspend fun assertAllDataDeleted(radio: RadioId, seed: Seed) {
        assertTrue(store.fetchContacts(radio).isEmpty())
        assertTrue(store.fetchChannels(radio).isEmpty())
        assertTrue(store.fetchReactions(seed.message).isEmpty())
        assertTrue(store.fetchRemoteNodeSessions(radio).isEmpty())
        assertTrue(store.fetchRoomMessages(seed.session).isEmpty())
        assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
        assertTrue(store.fetchRxLogEntries(radio).isEmpty())
        assertTrue(store.fetchDiscoveredNodes(radio).isEmpty())
        assertTrue(store.fetchMessageRepeats(seed.message).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteDevice removes only device record, preserves all associated data()")
    @Test fun registryDeletionRetainsTheCompleteOriginalSeedAndEightPreservationCounts() = runTest {
        val device = device()
        val seeded = seedAllEntityTypes(device.radioId)
        store.deleteDevice(device.id)

        assertNull(store.fetchDevice(device.id))
        assertAllDataExists(device.radioId, seeded)
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceData removes all associated data but not device record()")
    @Test fun dataDeletionRetainsTheCompleteOriginalSeedAndDevicePlusNineEmptyPredicates() = runTest {
        val device = device()
        val seeded = seedAllEntityTypes(device.radioId)
        store.deleteDeviceData(device.id)

        assertNotNull(store.fetchDevice(device.id))
        assertAllDataDeleted(device.radioId, seeded)
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceAndData removes device and all data atomically()")
    @Test fun combinedDeletionRetainsTheCompleteOriginalSeedAndDevicePlusNineEmptyPredicates() = runTest {
        val device = device()
        val seeded = seedAllEntityTypes(device.radioId)
        store.deleteDeviceAndData(device.id)

        assertNull(store.fetchDevice(device.id))
        assertAllDataDeleted(device.radioId, seeded)
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceAndData does not trap when a message has heard repeats (cascade-vs-batch)()")
    @Test fun combinedRepeatDeletionUsesOnlyTheOriginalBroadcastAndTwoIndependentDefaultRepeats() = runTest {
        val device = device()
        val message = message(device.radioId, "broadcast")
        repeat(message)
        repeat(message)
        store.deleteDeviceAndData(device.id)

        assertNull(store.fetchDevice(device.id))
        assertNull(store.fetchMessage(message))
        assertTrue(store.fetchMessageRepeats(message).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceData reaps a message and its heard repeats while preserving the device()")
    @Test fun dataRepeatDeletionUsesOnlyTheOriginalBroadcastAndTwoIndependentDefaultRepeats() = runTest {
        val device = device()
        val message = message(device.radioId, "broadcast")
        repeat(message)
        repeat(message)
        store.deleteDeviceData(device.id)

        assertNotNull(store.fetchDevice(device.id))
        assertNull(store.fetchMessage(message))
        assertTrue(store.fetchMessageRepeats(message).isEmpty())
    }
}
