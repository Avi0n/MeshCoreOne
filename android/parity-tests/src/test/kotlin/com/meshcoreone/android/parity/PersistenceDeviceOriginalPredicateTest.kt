// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceDeviceOriginalPredicateTest : RoomProcessTest() {
    private fun device(keyByte: Int = 0x44): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { keyByte.toByte() }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
    }

    private fun frame(name: String = "TestContact") = ContactFrame(
        Bytes(ByteArray(32) { 0x55 }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
        Instant.now().epochSecond.toUInt(), 37.7749, -122.4194, Instant.now().epochSecond.toUInt(),
    )

    @OriginalCase("PersistenceStoreTests::Save and fetch device()")
    @Test fun singleDeviceReadbackRetainsAllFourOriginalPredicates() = runTest {
        val device = device()
        store.saveDevice(device)

        val fetched = store.fetchDevice(device.id)
        assertNotNull(fetched)
        assertEquals("TestDevice", fetched.nodeName)
        assertEquals(8u.toUByte(), fetched.firmwareVersion)
        assertEquals(915_000u, fetched.frequency)
    }

    @OriginalCase("PersistenceStoreTests::Fetch all devices()")
    @Test fun twoIndependentOriginalDevicesRetainTheExactRegistryCount() = runTest {
        val first = device()
        val second = device(0x45)
        store.saveDevice(first)
        store.saveDevice(second)

        assertEquals(2, store.fetchDevices().size)
    }

    @OriginalCase("PersistenceStoreTests::Set active device()")
    @Test fun activationRetainsTheOriginalTwoStepAndEveryIntermediatePredicate() = runTest {
        val first = device()
        val second = device(0x45)
        store.saveDevice(first)
        store.saveDevice(second)
        store.setActiveDevice(first.id)

        val active = store.fetchActiveDevice()
        assertEquals(first.id, active?.id)
        assertEquals(true, active?.isActive)

        store.setActiveDevice(second.id)
        assertEquals(second.id, store.fetchActiveDevice()?.id)
        assertEquals(false, store.fetchDevice(first.id)?.isActive)
    }

    private suspend fun outgoingMessage(text: String): MessageDTO {
        val radio = RadioId(UUID.randomUUID())
        val contact = store.saveContact(radio, frame()).id
        return MessageDTO(
            radioId = radio, contactID = contact, text = text,
            timestamp = Instant.now().epochSecond.toUInt(), direction = MessageDirection.OUTGOING,
        ).also { store.saveMessage(it) }
    }

    @OriginalCase("PersistenceStoreTests::updateMessageRetryStatus does not resurrect a .failed row()")
    @Test fun staleRetryRetainsTheOriginalPendingFailedRetrySequenceWithoutAnAck() = runTest {
        val message = outgoingMessage("give-up race")
        val key = EntityKey(message.radioId, message.id)
        store.updateMessageStatus(key, MessageStatus.FAILED)
        store.updateMessageRetryStatus(key, MessageStatus.RETRYING, 1, 4)

        assertEquals(MessageStatus.FAILED, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::updateMessageRetryStatus still advances a non-terminal row()")
    @Test fun nonTerminalRetryRetainsTheOriginalDirectPendingToRetryingTransition() = runTest {
        val message = outgoingMessage("in flight")
        val key = EntityKey(message.radioId, message.id)
        store.updateMessageRetryStatus(key, MessageStatus.RETRYING, 0, 4)

        assertEquals(MessageStatus.RETRYING, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceData for non-existent device does not throw()")
    @Test fun absentDeviceDataDeletionRetainsItsIndependentEmptyStoreAction() = runTest {
        store.deleteDeviceData(UUID.randomUUID())

        assertTrue(store.fetchDevices().isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceAndData for non-existent device does not throw()")
    @Test fun absentCombinedDeletionRetainsItsIndependentEmptyStoreAction() = runTest {
        store.deleteDeviceAndData(UUID.randomUUID())

        assertTrue(store.fetchDevices().isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::Re-pair after device deletion re-associates orphaned data()")
    @Test fun rePairRetainsOriginalSurvivorAndGeneralRowsAndAllFourPredicates() = runTest {
        val device = device()
        store.saveDevice(device)
        store.saveContact(device.radioId, frame("Survivor"))
        store.saveChannel(device.radioId, ChannelInfo(0u, "General", Bytes(ByteArray(16))))
        store.deleteDevice(device.id)
        store.saveDevice(device)

        val contacts = store.fetchContacts(device.radioId)
        assertEquals(1, contacts.size)
        assertEquals("Survivor", contacts.firstOrNull()?.name)
        val channels = store.fetchChannels(device.radioId)
        assertEquals(1, channels.size)
        assertEquals("General", channels.firstOrNull()?.name)
    }

    @OriginalCase("PersistenceStoreTests::Demote device to ghost preserves publicKey and radioID with fresh id()")
    @Test fun demotionRetainsOriginalActiveDeviceWithoutAddedHandlesOrChildren() = runTest {
        val original = device().copy(isActive = true)
        store.saveDevice(original)
        store.demoteDeviceToGhost(original.id)

        assertNull(store.fetchDevice(original.id))
        val ghost = store.fetchDevice(original.publicKey)
        assertNotNull(ghost)
        assertNotEquals(original.id, ghost.id)
        assertEquals(original.publicKey, ghost.publicKey)
        assertEquals(original.radioId, ghost.radioId)
        assertEquals(false, ghost.isActive)
    }

    @OriginalCase("PersistenceStoreTests::Demote device strips all connection methods so it stays hidden()")
    @Test fun hiddenGhostRetainsOriginalInactiveDeviceAndBothUndisplayedConnectionMethods() = runTest {
        val wifi = ConnectionMethod.WiFi("10.0.0.5", 5000u)
        val bluetooth = ConnectionMethod.Bluetooth(UUID.randomUUID())
        val original = device().copy(connectionMethods = SnapshotList.of(wifi, bluetooth))
        store.saveDevice(original)
        store.demoteDeviceToGhost(original.id)

        assertEquals(true, store.fetchDevice(original.publicKey)?.connectionMethods?.isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::Demote device with unknown id is a no-op()")
    @Test fun unknownDemotionRetainsItsIndependentEmptyStoreAndEmptyRegistryPredicate() = runTest {
        store.demoteDeviceToGhost(UUID.randomUUID())

        assertTrue(store.fetchDevices().isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::Removing a paired device preserves child contacts via radioID()")
    @Test fun removedPairingRetainsOriginalInactiveHandlelessDeviceAndAliceChild() = runTest {
        val device = device()
        store.saveDevice(device)
        store.saveContact(device.radioId, frame("Alice"))
        store.demoteDeviceToGhost(device.id)

        assertEquals(device.radioId, store.fetchDevice(device.publicKey)?.radioId)
        val contacts = store.fetchContacts(device.radioId)
        assertEquals(1, contacts.size)
        assertEquals("Alice", contacts.firstOrNull()?.name)
    }
}
