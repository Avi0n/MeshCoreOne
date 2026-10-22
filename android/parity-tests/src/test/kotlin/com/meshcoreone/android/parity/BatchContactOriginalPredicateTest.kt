// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreBatchSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/PersistenceStore+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ContactDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatchContactOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())

    @Before fun seedOriginalDevice() = runBlocking {
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = publicKey(1), nodeName = "TestDevice",
            firmwareVersion = 8u, firmwareVersionString = "v1.0.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025", maxContacts = 100u,
            maxChannels = 8u, multiAcks = 0u, lastConnected = Instant.ofEpochSecond(1_704_067_200),
            lastContactSync = 0u, isActive = true,
        )
        store.saveDevice(device)
        assertEquals(device, store.fetchDevice(radio))
    }

    @Test fun batchInsertRequiresCountThreeAndAllThreeActualOriginalContacts() = runTest {
        val frames = SnapshotList.of(frame(1, "Alice"), frame(2, "Bob"), frame(3, "Carol"))

        val count = store.batchSaveContacts(radio, frames)

        assertEquals(3L, count)
        val stored = store.fetchContacts(radio)
        assertEquals(3, stored.size)
        assertEquals(setOf("Alice", "Bob", "Carol"), stored.map { it.name }.toSet())
        for (source in frames) {
            val actual = requireNotNull(store.fetchContact(radio, source.publicKey))
            assertOriginalFrame(actual, source)
            assertFalse(actual.isFavorite)
        }
    }

    @Test fun favoriteOriginalRequiresOneStoredRenamedRowWithoutAvatar() = runTest {
        store.saveContact(radio, frame(1, "Original", 1u))
        val original = requireNotNull(store.fetchContact(radio, publicKey(1)))
        assertOriginalFrame(original, frame(1, "Original", 1u))
        assertTrue(original.isFavorite)
        assertNull(original.avatarImageData)

        val count = store.batchSaveContacts(radio, SnapshotList.of(frame(1, "Renamed", 0u)))

        assertEquals(1L, count)
        val stored = store.fetchContacts(radio)
        assertEquals(1, stored.size)
        val actual = requireNotNull(stored.firstOrNull())
        assertEquals("Renamed", actual.name)
        assertTrue(actual.isFavorite)
        assertEquals(original.id, actual.id)
        assertOriginalFrame(actual, frame(1, "Renamed", 1u))
        assertNull(actual.avatarImageData)
    }

    @Test fun nonFavoriteAvatarOriginalPreservesFullThirtyTwoByteImageByKeyFetch() = runTest {
        val jpeg = Bytes(ByteArray(32) { 0xAB.toByte() })
        val original = ContactDTO(
            id = UUID.randomUUID(), radioId = radio, publicKey = publicKey(1), name = "Original",
            typeRawValue = ContactType.CHAT.rawValue, flags = 0u, outPathLength = 255u,
            outPath = Bytes.EMPTY, lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
            lastModified = 0u, lastHeardTimestamp = null, nickname = null, isBlocked = false,
            isMuted = false, isFavorite = false, lastMessageDate = null, unreadCount = 0,
            unreadMentionCount = 0, avatarImageData = jpeg,
        )
        store.saveContact(original)
        assertEquals(original.copy(lastHeardTimestamp = 0u), store.fetchContact(radio, publicKey(1)))

        store.batchSaveContacts(radio, SnapshotList.of(frame(1, "Renamed")))

        val stored = requireNotNull(store.fetchContact(radio, publicKey(1)))
        assertEquals("Renamed", stored.name)
        assertEquals(jpeg, stored.avatarImageData)
        assertEquals(original.id, stored.id)
        assertFalse(stored.isFavorite)
        assertOriginalFrame(stored, frame(1, "Renamed"))
    }

    private fun publicKey(byte: Int) = Bytes(ByteArray(32) { byte.toByte() })

    private fun frame(byte: Int, name: String, flags: UByte = 0u) = ContactFrame(
        publicKey(byte), ContactType.CHAT, flags, 0u, Bytes.EMPTY, name, 0u, 0.0, 0.0, 0u,
    )

    private fun assertOriginalFrame(actual: ContactDTO, expected: ContactFrame) {
        assertEquals(radio, actual.radioId)
        assertEquals(expected.publicKey, actual.publicKey)
        assertEquals(expected.name, actual.name)
        assertEquals(1.toUByte(), actual.typeRawValue)
        assertEquals(expected.flags, actual.flags)
        assertEquals(0.toUByte(), actual.outPathLength)
        assertTrue(actual.outPath.isEmpty)
        assertEquals(0u, actual.lastAdvertTimestamp)
        assertEquals(0.0, actual.latitude, 0.0)
        assertEquals(0.0, actual.longitude, 0.0)
        assertEquals(0u, actual.lastModified)
    }
}
