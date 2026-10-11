// AndroidOnly: WP-407 Real app/Room/protocol integration, not a parallel messaging implementation or physical-radio evidence.
package com.meshcoreone.android.app.platform

import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.feature.chats.rooms.RoomConversationEvent
import org.junit.Assert.*
import org.junit.Test

class ChatRegistrationTest : RoomProcessTest() {
    @Test fun roomEventsUsePersistedRadioAndSessionIdentityRatherThanForeignPayloads() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID.randomUUID())
            val room = RemoteNodeSessionDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 6 }),
                name = "Synthetic room", role = RemoteNodeRole.ROOM_SERVER)
            val foreignRoom = room.copy(radioId = RadioId(UUID.randomUUID()))
            store.saveRemoteNodeSession(room)
            store.saveRemoteNodeSession(foreignRoom)
            val message = RoomMessageDTO(sessionID = room.id, authorKeyPrefix = Bytes(ByteArray(6) { 5 }),
                text = "Persisted selected radio", timestamp = 1u)
            val foreign = message.copy(text = "Must not render another radio's payload")
            store.saveRoomMessage(radio, message)
            store.saveRoomMessage(foreignRoom.radioId, foreign)
            val seen = mutableListOf<RoomConversationEvent>()
            backgroundScope.launch { h.container.chatsFeature.room(room).events.collect { seen += it } }
            h.settle()
            h.appState.messageEventStream.send(MessageEvent.RoomMessageReceived(foreign, room.id))
            h.eventually("room message received from selected partition") {
                seen.any { it is RoomConversationEvent.MessageReceived }
            }
            assertEquals(message, seen.filterIsInstance<RoomConversationEvent.MessageReceived>().single().message)
            val unrelated = message.copy(id = UUID.randomUUID(), sessionID = UUID.randomUUID(), text = "Other room")
            store.saveRoomMessage(radio, unrelated)
            h.appState.messageEventStream.send(MessageEvent.RoomMessageStatusUpdated(unrelated.id))
            h.settle()
            assertTrue(seen.filterIsInstance<RoomConversationEvent.MessageChanged>().isEmpty())
        } finally { h.container.close() }
    }

    @Test fun offlineConversationMutationsUseTheCapturedRadioPartition() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val first = contact(RadioId(UUID.randomUUID()))
            val second = first.copy(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 9 }))
            store.saveContact(first); store.saveContact(second)
            val bound = h.container.chatsFeature.listFor(first.radioId)
            bound.data.setContactMuted(first.id, true)
            assertTrue(requireNotNull(store.fetchContact(EntityKey(first.radioId, first.id))).isMuted)
            assertFalse(requireNotNull(store.fetchContact(EntityKey(second.radioId, second.id))).isMuted)
            assertEquals(first.radioId, bound.currentRadioId.value)
            assertEquals(0, h.lifecycle.created.size)
        } finally { h.container.close() }
    }

    @Test fun realPendingSendAppearsInMountedTimelineAndKeepsOriginalText() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val contact = contact(requireNotNull(h.appState.currentRadioId))
            store.saveContact(contact)
            val feature = h.container.chatsFeature
            val conversation = TimelineConversation.Direct(contact)
            val timeline = ChatViewModel(conversation, feature.timeline(conversation), backgroundScope)
            timeline.start(); timeline.open(); h.settle()
            val text = "Original text \u4f60\u597d; not translated"
            val pending = feature.sender(contact.radioId).createPendingDirect(text, contact, TextType.PLAIN, null)
            h.eventually("pending message visible") { timeline.state.value.messages.any { it.id == pending.id } }
            assertEquals(text, timeline.state.value.messages.single { it.id == pending.id }.text)
            assertEquals(text, requireNotNull(store.fetchMessage(EntityKey(contact.radioId, pending.id))).text)
            assertEquals(1, h.lifecycle.created.size)
        } finally { h.container.close() }
    }

    @Test fun notificationSuppressionFollowsTheVisibleConversationAndClearsOnDispose() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val contact = contact(requireNotNull(h.appState.currentRadioId))
            val service = requireNotNull(h.sessions.current).notificationService
            val deactivate = h.container.chatsFeature.activate(ChatSelection.Direct(contact))
            assertEquals(contact.id, service.activeConversation.contactID)
            deactivate()
            assertNull(service.activeConversation.contactID)
        } finally { h.container.close() }
    }

    @Test fun processCloseRemovesNativeChatCollectors() = runTest {
        val h = ContainerHarness(this, store)
        h.container.chatsFeature
        h.settle()
        assertTrue(h.appState.messageEventStream.subscriberCount > 0)
        h.container.close()
        h.settle()
        assertEquals(0, h.appState.messageEventStream.subscriberCount)
    }

    private fun contact(radio: RadioId) = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }),
        name = "Synthetic peer", lastHeardTimestamp = null)
}
