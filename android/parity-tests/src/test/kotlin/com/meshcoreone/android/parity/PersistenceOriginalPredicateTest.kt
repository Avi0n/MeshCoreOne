// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceOriginalPredicateTest : RoomProcessTest() {
    private fun device(): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = key(0x44), nodeName = "TestDevice",
            firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
    }

    private fun key(byte: Int): Bytes = Bytes(ByteArray(32) { byte.toByte() })

    private fun frame(name: String, publicKey: Bytes = key(0x44)): ContactFrame {
        val timestamp = Instant.now().epochSecond.toUInt()
        return ContactFrame(
            publicKey, ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
            timestamp, 37.7749, -122.4194, timestamp,
        )
    }

    @OriginalCase("PersistenceStoreTests::Fetch contact by public key()")
    @Test fun publicKeyLookupRetainsTheOriginalBobPredicate() = runTest {
        val device = device()
        store.saveDevice(device)
        val frame = frame("Bob")
        store.saveContact(device.radioId, frame)

        val fetched = store.fetchContact(device.radioId, frame.publicKey)
        assertNotNull(fetched)
        assertEquals("Bob", fetched.name)
    }

    @OriginalCase("PersistenceStoreTests::deleteContacts removes matching rows in one pass()")
    @Test fun aSingleBatchDeletesBothOriginalContactKeysAndTheirMessage() = runTest {
        val device = device()
        store.saveDevice(device)
        val keyA = key(0xA1)
        val keyB = key(0xA2)
        val a = store.saveContact(device.radioId, ContactFrame(
            keyA, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, "A", 1u, 0.0, 0.0, 1u,
        )).id
        val b = store.saveContact(device.radioId, ContactFrame(
            keyB, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, "B", 1u, 0.0, 0.0, 1u,
        )).id
        store.saveMessage(MessageDTO(
            radioId = device.radioId, contactID = a, text = "gone", timestamp = Instant.now().epochSecond.toUInt(),
        ))

        val deleted = store.deleteContacts(device.radioId, listOf(keyA, keyB).snapshotSet())
        assertEquals(setOf(a, b), deleted.toSet())
        assertNull(store.fetchContact(EntityKey(device.radioId, a)))
        assertNull(store.fetchContact(EntityKey(device.radioId, b)))
        assertTrue(store.fetchMessages(EntityKey(device.radioId, a), 10, 0).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteContactIfUnreferenced skips when messages exist and deletes when none()")
    @Test fun aDistinctBareContactIsRemovedWithoutDeletingReferencedHistory() = runTest {
        val device = device()
        store.saveDevice(device)
        val withMessages = store.saveContact(device.radioId, frame("WithMsgs")).id
        store.saveMessage(MessageDTO(
            radioId = device.radioId, contactID = withMessages, text = "keep",
            timestamp = Instant.now().epochSecond.toUInt(),
        ))
        val referenced = EntityKey(device.radioId, withMessages)
        store.deleteContactIfUnreferenced(referenced)
        assertNotNull(store.fetchContact(referenced))
        assertEquals(1, store.fetchMessages(referenced, 10, 0).size)

        val bare = store.saveContact(device.radioId, frame("Bare", key(0x45))).id
        store.deleteContactIfUnreferenced(EntityKey(device.radioId, bare))
        assertNull(store.fetchContact(EntityKey(device.radioId, bare)))
    }

    @OriginalCase("PersistenceStoreTests::newestUnreadIncomingMessage returns the newest unread incoming only()")
    @Test fun newestUnreadSelectionRetainsTheOriginalRowIdentityAndText() = runTest {
        val device = device()
        store.saveDevice(device)
        val a = store.saveContact(device.radioId, frame("A")).id
        val b = store.saveContact(device.radioId, frame("B", key(0x45))).id
        val base = Instant.ofEpochSecond(1_700_000_000)
        suspend fun save(text: String, contact: UUID, direction: MessageDirection, read: Boolean, offset: Long): UUID {
            val date = base.plusSeconds(offset)
            val message = MessageDTO(
                radioId = device.radioId, contactID = contact, text = text,
                timestamp = date.epochSecond.toUInt(), createdAt = date, sortDate = date,
                direction = direction, isRead = read,
            )
            store.saveMessage(message)
            return message.id
        }
        save("old unread incoming", a, MessageDirection.INCOMING, false, 10)
        val newest = save("new unread incoming", a, MessageDirection.INCOMING, false, 30)
        save("newer but read", a, MessageDirection.INCOMING, true, 40)
        save("newer but outgoing", a, MessageDirection.OUTGOING, false, 50)
        save("other contact unread", b, MessageDirection.INCOMING, false, 60)

        val result = store.newestUnreadIncomingMessage(EntityKey(device.radioId, a))
        assertEquals(newest, result?.id)
        assertEquals("new unread incoming", result?.text)
    }

    @OriginalCase("PersistenceStoreTests::shared updateMessageStatusUnlessDelivered still remaps .failed to .pending for the offline queue()")
    @Test fun offlineRecoveryPersistsPendingBeforeTheSeparateSuccessfulAck() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val message = MessageDTO(
            radioId = radio, contactID = UUID.randomUUID(), text = "Test message",
            timestamp = Instant.now().epochSecond.toUInt(), status = MessageStatus.FAILED,
        )
        store.saveMessage(message)
        val key = EntityKey(radio, message.id)

        assertTrue(store.updateMessageStatusUnlessDelivered(key, MessageStatus.PENDING))
        assertEquals(MessageStatus.PENDING, store.fetchMessage(key)?.status)
        store.updateMessageAck(key, 0x1234_5678u, MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(key)?.status)
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity rewrites current device when ghost matches publicKey()")
    @Test fun aDifferentCurrentKeyIsRewrittenToTheOriginalGhostPartition() = runTest {
        val ghost = device().copy(radioId = RadioId(UUID.randomUUID()), publicKey = key(0x11))
        val current = device().copy(
            radioId = RadioId(UUID.randomUUID()), publicKey = key(0x22), isActive = true,
        )
        store.saveDevice(ghost)
        store.saveDevice(current)

        assertEquals(ghost.radioId, store.reconcileGhostIdentity(current.id, ghost.publicKey))
        val updated = store.fetchDevice(current.id)
        assertEquals(ghost.radioId, updated?.radioId)
        assertEquals(ghost.publicKey, updated?.publicKey)
        assertNull(store.fetchDevice(ghost.id))
    }

    @OriginalCase("PersistenceStoreTests::Set contact muted()")
    @Test fun mutingRetainsTheInitialFalseAndBothOriginalTransitions() = runTest {
        val device = device()
        store.saveDevice(device)
        val id = store.saveContact(device.radioId, frame("Alice")).id
        val key = EntityKey(device.radioId, id)

        assertFalse(assertNotNull(store.fetchContact(key)).isMuted)
        store.setContactMuted(key, true)
        assertTrue(assertNotNull(store.fetchContact(key)).isMuted)
        store.setContactMuted(key, false)
        assertFalse(assertNotNull(store.fetchContact(key)).isMuted)
    }
}
