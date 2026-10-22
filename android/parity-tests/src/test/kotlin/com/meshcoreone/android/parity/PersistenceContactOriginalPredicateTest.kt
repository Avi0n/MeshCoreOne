// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceContactOriginalPredicateTest : RoomProcessTest() {
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

    @OriginalCase("PersistenceStoreTests::Save and fetch contact from frame()")
    @Test fun originalAliceReadbackRetainsTheIndependentParentAndAllThreePredicates() = runTest {
        val device = device()
        val contactID = store.saveContact(device.radioId, frame("Alice")).id

        val contact = store.fetchContact(EntityKey(device.radioId, contactID))
        assertNotNull(contact)
        assertEquals("Alice", contact.name)
        assertEquals(ContactType.CHAT, contact.type)
    }

    @OriginalCase("PersistenceStoreTests::Update contact last message and unread count()")
    @Test fun originalUnreadTransitionRetainsCurrentDateTwoIncrementsAndAllIntermediateReads() = runTest {
        val device = device()
        val contactID = store.saveContact(device.radioId, frame()).id
        val key = EntityKey(device.radioId, contactID)
        val now = Instant.now()
        store.updateContactLastMessage(key, now)
        store.incrementUnreadCount(key)
        store.incrementUnreadCount(key)

        val contact = store.fetchContact(key)
        assertEquals(2L, contact?.unreadCount)
        assertNotNull(contact?.lastMessageDate)
        store.clearUnreadCount(key)
        assertEquals(0L, store.fetchContact(key)?.unreadCount)
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForContact removes all messages for a contact()")
    @Test fun originalFiveAndOneContactHistoriesRetainEveryPreAndPostDeletionCount() = runTest {
        val device = device()
        val firstID = store.saveContact(device.radioId, frame("Contact1")).id
        for (i in 0..<5) {
            store.saveMessage(MessageDTO(
                radioId = device.radioId, contactID = firstID, text = "Message $i",
                timestamp = Instant.now().epochSecond.toUInt() + i.toUInt(),
            ))
        }
        val secondID = store.saveContact(device.radioId, frame("Contact2", 0x56)).id
        store.saveMessage(MessageDTO(
            radioId = device.radioId, contactID = secondID, text = "Other message",
            timestamp = Instant.now().epochSecond.toUInt() + 100u,
        ))
        val first = EntityKey(device.radioId, firstID)
        val second = EntityKey(device.radioId, secondID)
        assertEquals(5, store.fetchMessages(first).size)
        assertEquals(1, store.fetchMessages(second).size)
        store.deleteMessagesForContact(first)

        assertTrue(store.fetchMessages(first).isEmpty())
        assertEquals(1, store.fetchMessages(second).size)
    }

    @OriginalCase("PersistenceStoreTests::recomputeContactLastMessageDate keeps a conversation visible while older messages remain and clears it only when empty()")
    @Test fun originalThreeMessagesRetainAllDateAndConversationMembershipTransitions() = runTest {
        val device = device()
        val contactID = store.saveContact(device.radioId, frame("Conversation")).id
        val key = EntityKey(device.radioId, contactID)
        val base = Instant.ofEpochSecond(1_700_000_000)
        val messages = (0..<3).map { i ->
            MessageDTO(
                radioId = device.radioId, contactID = contactID, text = "Message $i",
                timestamp = base.epochSecond.toUInt() + i.toUInt(), createdAt = base.plusSeconds(i.toLong()),
            )
        }
        for (message in messages) store.saveMessage(message)

        assertEquals(messages[2].date, store.recomputeContactLastMessageDate(key))
        assertTrue(store.fetchConversations(device.radioId).any { it.id == contactID })
        store.deleteMessage(EntityKey(device.radioId, messages[2].id))
        assertEquals(messages[1].date, store.recomputeContactLastMessageDate(key))
        assertTrue(store.fetchConversations(device.radioId).any { it.id == contactID })
        store.deleteMessage(EntityKey(device.radioId, messages[1].id))
        store.deleteMessage(EntityKey(device.radioId, messages[0].id))
        assertNull(store.recomputeContactLastMessageDate(key))
        assertTrue(store.fetchConversations(device.radioId).none { it.id == contactID })
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel removes all messages for a channel()")
    @Test fun originalFiveThreeAndOneHistoriesRetainEveryChannelAndContactPredicate() = runTest {
        val device = device()
        for (i in 0..<5) {
            store.saveMessage(MessageDTO(
                radioId = device.radioId, channelIndex = 0u, text = "Channel 0 Message $i",
                timestamp = Instant.now().epochSecond.toUInt() + i.toUInt(),
            ))
        }
        for (i in 0..<3) {
            store.saveMessage(MessageDTO(
                radioId = device.radioId, channelIndex = 1u, text = "Channel 1 Message $i",
                timestamp = Instant.now().epochSecond.toUInt() + (i + 100).toUInt(),
            ))
        }
        val contactID = store.saveContact(device.radioId, frame("Contact1")).id
        val contact = EntityKey(device.radioId, contactID)
        store.saveMessage(MessageDTO(
            radioId = device.radioId, contactID = contactID, text = "Contact message",
            timestamp = Instant.now().epochSecond.toUInt() + 200u,
        ))
        assertEquals(5, store.fetchMessages(device.radioId, 0u).size)
        assertEquals(3, store.fetchMessages(device.radioId, 1u).size)
        assertEquals(1, store.fetchMessages(contact).size)
        store.deleteMessagesForChannel(device.radioId, 0u)

        assertTrue(store.fetchMessages(device.radioId, 0u).isEmpty())
        assertEquals(3, store.fetchMessages(device.radioId, 1u).size)
        assertEquals(1, store.fetchMessages(contact).size)
    }
}
