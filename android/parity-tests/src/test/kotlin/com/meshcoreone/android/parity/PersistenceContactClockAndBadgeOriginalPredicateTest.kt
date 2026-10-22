// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceContactClockAndBadgeOriginalPredicateTest : RoomProcessTest() {
    private suspend fun device(): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        ).also { store.saveDevice(it) }
    }

    private fun frame(name: String, keyByte: Int = 0x55) = ContactFrame(
        Bytes(ByteArray(32) { keyByte.toByte() }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
        Instant.now().epochSecond.toUInt(), 37.7749, -122.4194, Instant.now().epochSecond.toUInt(),
    )

    @OriginalCase("PersistenceStoreTests::updateDeviceLastContactSync stores radio-ahead watermark without phone clamp()")
    @Test fun originalThirtyDayRadioAheadWatermarkRetainsRequiredDeviceAndUnclampedReadback() = runTest {
        val device = device()
        val phoneNow = Instant.now().epochSecond.toUInt()
        val radioAhead = phoneNow + (30 * 24 * 60 * 60).toUInt()
        store.updateDeviceLastContactSync(device.radioId, radioAhead)

        val fetched = assertNotNull(store.fetchDevice(device.radioId))
        assertEquals(radioAhead, fetched.lastContactSync)
    }

    @OriginalCase("PersistenceStoreTests::saveContact from frame returns isNew true then false with stable id()")
    @Test fun originalStableThenRenamedFrameRetainsBothIsNewFlagsStableIdAndOptionalName() = runTest {
        val device = device()
        val frame = frame("Stable")
        val first = store.saveContact(device.radioId, frame)
        assertTrue(first.isNew)
        val updated = frame.copy(
            name = "Renamed", lastAdvertTimestamp = frame.lastAdvertTimestamp + 1u,
            lastModified = frame.lastModified + 1u,
        )
        val second = store.saveContact(device.radioId, updated)
        assertFalse(second.isNew)
        assertEquals(first.id, second.id)
        assertEquals("Renamed", store.fetchContact(EntityKey(device.radioId, first.id))?.name)
    }

    @OriginalCase("PersistenceStoreTests::touchContactHeard bumps contact and existing discovered node()")
    @Test fun originalExistingDiscoveredNodeRetainsRequiredContactNodeStampAndUnknownEeFalse() = runTest {
        val device = device()
        val frame = frame("Heard")
        store.saveContact(device.radioId, frame)
        store.upsertDiscoveredNode(device.radioId, frame)
        val stamp = Instant.ofEpochSecond(1_800_000_000)
        assertTrue(store.touchContactHeard(device.radioId, frame.publicKey, stamp))
        val contact = assertNotNull(store.fetchContact(device.radioId, frame.publicKey))
        assertEquals(1_800_000_000u, contact.lastHeardTimestamp)
        val nodes = store.fetchDiscoveredNodes(device.radioId)
        val node = assertNotNull(nodes.firstOrNull { it.publicKey == frame.publicKey })
        assertEquals(stamp, node.lastHeard)
        assertFalse(store.touchContactHeard(device.radioId, Bytes(ByteArray(32) { 0xEE.toByte() }), stamp))
    }

    @OriginalCase("PersistenceStoreTests::touchContactHeard creates a missing discovered node()")
    @Test fun originalMissingDiscoveredNodeRetainsInitialEmptyRequiredMatchAndAllSixFrameFields() = runTest {
        val device = device()
        val frame = frame("NoDiscoverRow")
        store.saveContact(device.radioId, frame)
        assertTrue(store.fetchDiscoveredNodes(device.radioId).isEmpty())
        val stamp = Instant.ofEpochSecond(1_800_000_500)
        assertTrue(store.touchContactHeard(device.radioId, frame.publicKey, stamp))
        val nodes = store.fetchDiscoveredNodes(device.radioId)
        val node = assertNotNull(nodes.firstOrNull { it.publicKey == frame.publicKey })
        assertEquals(frame.name, node.name)
        assertEquals(frame.type.rawValue, node.typeRawValue)
        assertEquals(frame.lastAdvertTimestamp, node.lastAdvertTimestamp)
        assertEquals(frame.latitude, node.latitude)
        assertEquals(frame.longitude, node.longitude)
        assertEquals(stamp, node.lastHeard)
    }

    @OriginalCase("PersistenceStoreTests::Muted contacts excluded from badge count()")
    @Test fun originalAliceTwoAndMutedBobOneRetainIndependentContactsAndExactlyTwoUnread() = runTest {
        val device = device()
        val alice = store.saveContact(device.radioId, frame("Alice"))
        val aliceKey = EntityKey(device.radioId, alice.id)
        store.incrementUnreadCount(aliceKey)
        store.incrementUnreadCount(aliceKey)
        val bob = store.saveContact(device.radioId, frame("Bob", 0x56))
        val bobKey = EntityKey(device.radioId, bob.id)
        store.incrementUnreadCount(bobKey)
        store.setContactMuted(bobKey, true)

        assertEquals(2L, store.getTotalUnreadCounts(device.radioId).contacts)
    }

    @OriginalCase("PersistenceStoreTests::Notification levels affect badge count correctly()")
    @Test fun originalAllMutedMentionsOnlyStatesRetainFourIndependentChannelCountsAndMentionIncrement() = runTest {
        val device = device()
        val channel = store.saveChannel(device.radioId, ChannelInfo(1u, "Test", Bytes(ByteArray(16) { 0x42 })))
        val key = EntityKey(device.radioId, channel)
        store.incrementChannelUnreadCount(key)
        store.incrementChannelUnreadCount(key)
        assertEquals(2L, store.getTotalUnreadCounts(device.radioId).channels)
        store.setChannelNotificationLevel(key, NotificationLevel.MUTED)
        assertEquals(0L, store.getTotalUnreadCounts(device.radioId).channels)
        store.setChannelNotificationLevel(key, NotificationLevel.MENTIONS_ONLY)
        assertEquals(0L, store.getTotalUnreadCounts(device.radioId).channels)
        store.incrementChannelUnreadMentionCount(key)
        assertEquals(1L, store.getTotalUnreadCounts(device.radioId).channels)
    }
}
