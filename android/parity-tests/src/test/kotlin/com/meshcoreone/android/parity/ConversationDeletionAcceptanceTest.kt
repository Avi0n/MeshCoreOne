// AndroidOnly: WP-501 frozen single-message deletion must rederive direct-conversation recency through the actual App adapter.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationDeletionAcceptanceTest : RoomProcessTest() {
    private suspend fun seed(radio: RadioId, count: Int): Pair<ContactDTO, List<MessageDTO>> {
        val base = Instant.ofEpochSecond(1_700_000_000)
        val contact = ContactDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { 0x55 }),
            name = "Conversation", lastHeardTimestamp = null, lastMessageDate = base.plusSeconds((count - 1).toLong()),
        )
        store.saveContact(contact)
        val messages = (0 until count).map { i ->
            MessageDTO(
                radioId = radio, contactID = contact.id, text = "Message $i",
                timestamp = base.epochSecond.toUInt() + i.toUInt(), createdAt = base.plusSeconds(i.toLong()),
            )
        }
        for (message in messages) store.saveMessage(message)
        return contact to messages
    }

    @Test fun actualAppDeletionRecomputesRecencyFromRemainingDatabaseHistory() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = requireNotNull(harness.appState.currentRadioId)
            val (contact, messages) = seed(radio, 55)
            val key = EntityKey(radio, contact.id)
            val list = harness.container.chatsFeature.listFor(radio).data
            assertEquals(messages.last().date, store.fetchContact(key)?.lastMessageDate)
            assertTrue(list.fetchConversations(radio).any { it.id == contact.id })
            harness.container.chatsFeature.delete(messages.last())

            assertNull(store.fetchMessage(EntityKey(radio, messages.last().id)))
            assertEquals(54, store.fetchMessages(key, 100).size)
            assertEquals(messages[53].date, store.fetchContact(key)?.lastMessageDate)
            assertEquals(messages[53].date, list.fetchConversations(radio).single { it.id == contact.id }.lastMessageDate)
        } finally { harness.container.close() }
    }

    @Test fun actualAppDeletionClearsTheLastMessageDateAndRemovesTheEmptyConversation() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = requireNotNull(harness.appState.currentRadioId)
            val (contact, messages) = seed(radio, 1)
            val key = EntityKey(radio, contact.id)
            val list = harness.container.chatsFeature.listFor(radio).data
            assertNotNull(store.fetchContact(key)?.lastMessageDate)
            assertTrue(list.fetchConversations(radio).any { it.id == contact.id })
            harness.container.chatsFeature.delete(messages.single())

            assertNull(store.fetchMessage(EntityKey(radio, messages.single().id)))
            assertTrue(store.fetchMessages(key).isEmpty())
            assertNull(store.fetchContact(key)?.lastMessageDate)
            assertTrue(list.fetchConversations(radio).none { it.id == contact.id })
        } finally { harness.container.close() }
    }
}
