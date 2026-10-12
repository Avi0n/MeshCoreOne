// AndroidOnly: WP-307 Real App/Room regressions for database-derived direct-conversation recency after deletion.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationDeletionIntegrationTest : RoomProcessTest() {
    private fun contactKey(contact: ContactDTO) = EntityKey(contact.radioId, contact.id)
    private fun messageKey(message: MessageDTO) = EntityKey(message.radioId, message.id)

    private suspend fun connect(harness: ContainerHarness): RadioId {
        harness.manager.connect(harness.target())
        harness.eventually("ready deletion graph") { harness.manager.connectionState == DeviceConnectionState.READY }
        harness.settle()
        return assertNotNull(harness.appState.currentRadioId)
    }

    private suspend fun seed(radio: RadioId, count: Int): Pair<ContactDTO, List<MessageDTO>> {
        val contact = ContactDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { 3 }),
            name = "Deletion peer", lastHeardTimestamp = null,
        )
        store.saveContact(contact)
        val messages = List(count) { index ->
            val date = Instant.ofEpochSecond(1_700_000_000L + index)
            MessageDTO(
                radioId = radio, contactID = contact.id, text = "History $index",
                timestamp = date.epochSecond.toUInt(), createdAt = date, sortDate = date,
            )
        }
        for (message in messages) store.saveMessage(message)
        val current = contact.copy(lastMessageDate = messages.last().date)
        store.saveContact(current)
        return current to messages
    }

    @Test fun deletingNewestRecomputesFromHistoryBeyondTheDefaultPage() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 55)
            val feature = harness.container.chatsFeature
            assertEquals(messages.last().date, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(50, store.fetchMessages(contactKey(contact)).size)
            assertEquals(contact.id, feature.list.data.fetchConversations(contact.radioId).single().id)

            feature.delete(messages.last())

            assertNull(store.fetchMessage(messageKey(messages.last())))
            assertEquals(54, store.fetchMessages(contactKey(contact), Long.MAX_VALUE).size)
            assertEquals(messages[53].date, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(messages[53].date, feature.list.data.fetchConversations(contact.radioId).single().lastMessageDate)
        } finally { harness.container.close() }
    }

    @Test fun deletingTheLastMessageClearsRecencyAndRemovesTheConversation() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 1)
            val feature = harness.container.chatsFeature
            assertEquals(messages.single().date, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(contact.id, feature.list.data.fetchConversations(contact.radioId).single().id)

            feature.delete(messages.single())

            assertNull(store.fetchMessage(messageKey(messages.single())))
            assertTrue(store.fetchMessages(contactKey(contact)).isEmpty())
            assertNull(assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertTrue(feature.list.data.fetchConversations(contact.radioId).isEmpty())
        } finally { harness.container.close() }
    }

    @Test fun deletingOlderHistoryKeepsTheNewestConversationDate() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 2)

            harness.container.chatsFeature.delete(messages.first())

            assertNull(store.fetchMessage(messageKey(messages.first())))
            assertEquals(listOf(messages.last()), store.fetchMessages(contactKey(contact)))
            assertEquals(messages.last().date, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(messages.last().date, store.fetchConversations(contact.radioId).single().lastMessageDate)
        } finally { harness.container.close() }
    }

    @Test fun deletionAndRecomputationKeepTheMessageRadioPartition() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 2)
            val otherContact = contact.copy(radioId = RadioId(UUID.randomUUID()))
            store.saveContact(otherContact)
            val otherMessages = messages.map { it.copy(radioId = otherContact.radioId) }
            for (message in otherMessages) store.saveMessage(message)

            harness.container.chatsFeature.delete(messages.last())

            assertNull(store.fetchMessage(messageKey(messages.last())))
            assertEquals(messages.first().date, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(otherMessages.last(), store.fetchMessage(messageKey(otherMessages.last())))
            assertEquals(2, store.fetchMessages(contactKey(otherContact)).size)
            assertEquals(otherContact.lastMessageDate, assertNotNull(store.fetchContact(contactKey(otherContact))).lastMessageDate)
        } finally { harness.container.close() }
    }

    @Test fun channelAndContactlessDeletionDoNotRecomputeDirectRecency() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 1)
            val channel = messages.single().copy(id = UUID.randomUUID(), channelIndex = 1u)
            val contactless = messages.single().copy(id = UUID.randomUUID(), contactID = null)
            store.saveMessage(channel)
            store.saveMessage(contactless)
            val feature = harness.container.chatsFeature

            feature.delete(channel)
            assertNull(store.fetchMessage(messageKey(channel)))
            assertEquals(contact.lastMessageDate, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            feature.delete(contactless)
            assertNull(store.fetchMessage(messageKey(contactless)))
            assertEquals(contact.lastMessageDate, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            assertEquals(messages.single(), store.fetchMessage(messageKey(messages.single())))
        } finally { harness.container.close() }
    }

    @Test fun recencyWriteFailurePropagatesWithoutPretendingTheDeletionRolledBack() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            val (contact, messages) = seed(connect(harness), 2)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_recency BEFORE INSERT ON contacts " +
                    "BEGIN SELECT RAISE(ABORT, 'injected recency failure'); END",
            )
            try {
                assertFailsWith<PersistenceStoreException> {
                    harness.container.chatsFeature.delete(messages.last())
                }
                assertNull(store.fetchMessage(messageKey(messages.last())))
                assertEquals(messages.first(), store.fetchMessage(messageKey(messages.first())))
                assertEquals(contact.lastMessageDate, assertNotNull(store.fetchContact(contactKey(contact))).lastMessageDate)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_recency")
            }
        } finally { harness.container.close() }
    }
}
