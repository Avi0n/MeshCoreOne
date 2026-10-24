// PortedFrom: MC1Tests/Views/Chats/ChatViewModelFailedSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 The split list holder consumes the real App adapter and in-memory Room store.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.FailedSendConversationKeys
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ChatListDataSource
import com.meshcoreone.android.feature.chats.list.ChatListDiagnostics
import com.meshcoreone.android.feature.chats.list.ChatListEvent
import com.meshcoreone.android.feature.chats.list.ChatListFeatureDependencies
import com.meshcoreone.android.feature.chats.list.ChatListStateHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FailedSendBadgeRecoveryOriginalTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())

    @OriginalCase("ChatViewModelFailedSendTests::applyFailedSendKeys maps contact id onto the list set()")
    @Test fun contactKeyReachesTheRealListMembershipSet() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.refreshFailedSendIndicators()

            assertEquals(setOf(contact.id), holder.state.value.failedSendIds)
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            assertFalse(holder.state.value.conversationHasFailedSend(UUID.randomUUID()))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::applyFailedSendKeys unions channel ids()")
    @Test fun channelKeyUsesTheStoredChannelIdRatherThanItsSlot() = runTest {
        withRig {
            val channel = channel(4)
            val other = channel(7)
            store.saveMessage(message(channelIndex = channel.index))
            holder.refreshFailedSendIndicators()

            assertEquals(setOf(channel.id), holder.state.value.failedSendIds)
            assertTrue(holder.state.value.conversationHasFailedSend(channel.id))
            assertFalse(holder.state.value.conversationHasFailedSend(other.id))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::applyFailedSendKeys maps room session id()")
    @Test fun roomKeyReachesTheRealListMembershipSet() = runTest {
        withRig {
            val room = room()
            store.saveRoomMessage(radio, roomMessage(room))
            holder.refreshFailedSendIndicators()

            assertEquals(setOf(room.id), holder.state.value.failedSendIds)
            assertTrue(holder.state.value.conversationHasFailedSend(room.id))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::clearConversations empties the failed-send set()")
    @Test fun clearingConversationsClearsExistingFailureMembership() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.requestConversationReload()?.join()
            assertTrue(holder.state.value.contacts.any { it.id == contact.id })
            assertFalse(holder.state.value.failedSendIds.isEmpty())

            holder.clearConversations()

            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertFalse(holder.state.value.conversationHasFailedSend(contact.id))
            assertTrue(holder.state.value.allConversations.isEmpty())
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::refresh with empty keys clears a previously populated set()")
    @Test fun emptyStoredKeysReplaceRatherThanUnionWithPreviousBadges() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            store.markFailedSendsSeen(com.meshcoreone.android.core.contracts.domain.EntityKey(radio, contact.id))
            assertEquals(FailedSendConversationKeys.EMPTY, store.fetchFailedSendConversationKeys(radio))

            holder.refreshFailedSendIndicators()

            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertFalse(holder.state.value.conversationHasFailedSend(contact.id))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::refresh loads failed DM channel and room keys from the store()")
    @Test fun realRoomQueryUnionsAllThreeConversationKinds() = runTest {
        withRig {
            val contact = contact()
            val channel = channel(2)
            val room = room()
            store.saveMessage(message(contactID = contact.id))
            store.saveMessage(message(channelIndex = channel.index))
            store.saveRoomMessage(radio, roomMessage(room))

            holder.refreshFailedSendIndicators()

            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            assertTrue(holder.state.value.conversationHasFailedSend(channel.id))
            assertTrue(holder.state.value.conversationHasFailedSend(room.id))
            assertEquals(setOf(contact.id, channel.id, room.id), holder.state.value.failedSendIds)
            assertEquals(listOf(radio), queriedRadios)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::conversation reload loads failed-send indicators()")
    @Test fun ordinaryConversationReloadAlsoLoadsFailureMembership() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))

            assertNotNull(holder.requestConversationReload()).join()

            assertTrue(holder.state.value.contacts.any { it.id == contact.id })
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            assertEquals(listOf(radio), queriedRadios)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::refresh with no radio id clears the set()")
    @Test fun missingCurrentRadioClearsBadgesWithoutAnotherStoreRead() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            currentRadio.value = null

            holder.refreshFailedSendIndicators()

            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertEquals(listOf(radio), queriedRadios)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::messageFailed and peers always refresh()")
    @Test fun threeFailureOrResendEventsAlwaysRequestIndicatorRefresh() = runTest {
        withRig {
            val id = UUID.randomUUID()
            val events = listOf(
                MessageEvent.MessageFailed(id) to ChatListEvent.MESSAGE_FAILED,
                MessageEvent.MessageResent(id) to ChatListEvent.MESSAGE_RESENT,
                MessageEvent.RoomMessageFailed(id) to ChatListEvent.ROOM_MESSAGE_FAILED,
            )
            for ((event, expected) in events) {
                val actual = mapped(event)
                assertEquals(expected, actual)
                assertTrue(holder.shouldRefreshFailedSendIndicators(actual))
            }
            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertTrue(queriedRadios.isEmpty())
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::messageStatusResolved refreshes only when a badge is showing()")
    @Test fun directStatusResolutionRequiresAnExistingBadge() = runTest {
        withRig {
            val event = MessageEvent.MessageStatusResolved(UUID.randomUUID(), MessageStatus.DELIVERED)
            val actual = mapped(event)
            assertEquals(ChatListEvent.MESSAGE_STATUS_RESOLVED, actual)
            assertFalse(holder.shouldRefreshFailedSendIndicators(actual))
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.refreshFailedSendIndicators()

            assertTrue(holder.shouldRefreshFailedSendIndicators(mapped(event)))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::roomMessageStatusUpdated refreshes only when a badge is showing()")
    @Test fun roomStatusResolutionRequiresAnExistingBadge() = runTest {
        withRig {
            val event = MessageEvent.RoomMessageStatusUpdated(UUID.randomUUID())
            val actual = mapped(event)
            assertEquals(ChatListEvent.ROOM_MESSAGE_STATUS_UPDATED, actual)
            assertFalse(holder.shouldRefreshFailedSendIndicators(actual))
            val room = room()
            store.saveRoomMessage(radio, roomMessage(room))
            holder.refreshFailedSendIndicators()

            assertTrue(holder.shouldRefreshFailedSendIndicators(mapped(event)))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::other message events do not refresh()")
    @Test fun allEightOtherEventKindsLeaveIndicatorRefreshUnrequested() = runTest {
        withRig {
            val contact = contact()
            val failed = message(contactID = contact.id)
            store.saveMessage(failed)
            holder.refreshFailedSendIndicators()
            val incomingRoom = RoomMessageDTO(
                sessionID = UUID.randomUUID(), authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                text = "hi", timestamp = 1u, createdAt = NOW,
            )
            val events = listOf(
                MessageEvent.DirectMessageReceived(failed, contact) to ChatListEvent.DIRECT_MESSAGE_RECEIVED,
                MessageEvent.ChannelMessageReceived(failed, 0u) to ChatListEvent.CHANNEL_MESSAGE_RECEIVED,
                MessageEvent.RoomMessageReceived(incomingRoom, UUID.randomUUID()) to ChatListEvent.ROOM_MESSAGE_RECEIVED,
                MessageEvent.MessageRetrying(failed.id, 1, 4) to ChatListEvent.MESSAGE_RETRYING,
                MessageEvent.HeardRepeatRecorded(failed.id, 1) to ChatListEvent.HEARD_REPEAT_RECORDED,
                MessageEvent.ReactionReceived(failed.id, "\uD83D\uDC4D:1") to ChatListEvent.REACTION_RECEIVED,
                MessageEvent.MessagesRegionUpdated(listOf(failed.id)) to ChatListEvent.MESSAGES_REGION_UPDATED,
                MessageEvent.RoutingChanged(contact.id, true) to ChatListEvent.ROUTING_CHANGED,
            )
            for ((event, expected) in events) {
                val actual = mapped(event)
                assertEquals(expected, actual)
                assertFalse(holder.shouldRefreshFailedSendIndicators(actual))
            }
            assertEquals(setOf(contact.id), holder.state.value.failedSendIds)
            assertEquals(listOf(radio), queriedRadios)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::stale empty refresh does not overwrite a newer apply()")
    @Test fun staleEmptyRoomResultCannotEraseTheNewerFailureSet() = runTest {
        withRig {
            val contact = contact()
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            afterFetch = { keys ->
                assertEquals(FailedSendConversationKeys.EMPTY, keys)
                arrived.complete(Unit)
                release.await()
            }
            val first = async { holder.refreshFailedSendIndicators() }
            arrived.await()
            try {
                afterFetch = {}
                store.saveMessage(message(contactID = contact.id))
                holder.refreshFailedSendIndicators()
                assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            } finally {
                release.complete(Unit)
            }
            first.await()

            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            assertEquals(listOf(radio, radio), queriedRadios)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::cancelled in-flight refresh does not apply fetched keys()")
    @Test fun cancelledReadCannotApplyItsFetchedFailureSet() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            afterFetch = { keys ->
                assertEquals(setOf(contact.id), keys.contactIDs)
                arrived.complete(Unit)
                release.await()
            }
            val task = async { holder.refreshFailedSendIndicators() }
            arrived.await()
            task.cancel()
            release.complete(Unit)
            task.join()

            assertTrue(task.isCancelled)
            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::store throw leaves last-known badges()")
    @Test fun failedReadRetainsLastKnownBadgesAndReportsTheSameError() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            val failure = IllegalStateException("original store read fault")
            fetchFailure = failure

            holder.refreshFailedSendIndicators()

            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            assertEquals("Failed to load failed-send indicators", failures.single().first)
            assertSame(failure, failures.single().second)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::clearConversations drops an in-flight refresh apply()")
    @Test fun clearingConversationsInvalidatesAnAlreadyFetchedFailureSet() = runTest {
        withRig {
            val contact = contact()
            store.saveMessage(message(contactID = contact.id))
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            afterFetch = { keys ->
                assertEquals(setOf(contact.id), keys.contactIDs)
                arrived.complete(Unit)
                release.await()
            }
            val task = async { holder.refreshFailedSendIndicators() }
            arrived.await()
            try {
                holder.clearConversations()
                assertTrue(holder.state.value.failedSendIds.isEmpty())
            } finally {
                release.complete(Unit)
            }
            task.await()

            assertTrue(holder.state.value.failedSendIds.isEmpty())
            assertEquals(setOf(contact.id), store.fetchFailedSendConversationKeys(radio).contactIDs)
        }
    }

    private inner class Rig(val test: TestScope, val harness: ContainerHarness) {
        val currentRadio = MutableStateFlow<RadioId?>(radio)
        val queriedRadios = mutableListOf<RadioId>()
        val failures = mutableListOf<Pair<String, Throwable?>>()
        var afterFetch: suspend (FailedSendConversationKeys) -> Unit = {}
        var fetchFailure: Exception? = null
        private val native = harness.container.chatsFeature.listFor(radio)
        private val data = object : ChatListDataSource by native.data {
            override suspend fun fetchFailedSendConversationKeys(radioId: RadioId): FailedSendConversationKeys {
                queriedRadios += radioId
                fetchFailure?.let { throw it }
                val keys = native.data.fetchFailedSendConversationKeys(radioId)
                afterFetch(keys)
                return keys
            }
        }
        private val dependencies = object : ChatListFeatureDependencies by native {
            override val currentRadioId = currentRadio
            override val data = this@Rig.data
            override val diagnostics = ChatListDiagnostics { operation, failure -> failures += operation to failure }
        }
        val holder = ChatListStateHolder(dependencies, test.backgroundScope)

        suspend fun mapped(event: MessageEvent): ChatListEvent {
            val next = test.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { native.messageEvents.first() }
            harness.appState.messageEventStream.send(event)
            return next.await()
        }
    }

    private suspend fun TestScope.withRig(body: suspend Rig.() -> Unit) {
        val harness = ContainerHarness(this, store)
        try {
            val rig = Rig(this, harness)
            rig.body()
            assertTrue(harness.links.radios.isEmpty(), "Badge recovery must not establish a radio connection")
            assertTrue(harness.messagingDiagnostics.isEmpty())
        } finally {
            harness.container.close()
        }
    }

    private suspend fun contact(): ContactDTO {
        val id = UUID.randomUUID()
        return ContactDTO(
            id = id, radioId = radio, publicKey = Bytes(ByteArray(32) { id.hashCode().toByte() }),
            name = "Test", typeRawValue = ContactType.CHAT.rawValue, outPathLength = 0u,
            lastHeardTimestamp = null, lastMessageDate = NOW,
        ).also { store.saveContact(it) }
    }

    private suspend fun channel(index: Int): ChannelDTO =
        ChannelDTO(radioId = radio, index = index.toUByte(), name = "General", secret = Bytes.EMPTY,
            lastMessageDate = NOW).also { store.saveChannel(it) }

    private suspend fun room(): RemoteNodeSessionDTO {
        val id = UUID.randomUUID()
        return RemoteNodeSessionDTO(
            id = id, radioId = radio, publicKey = Bytes(ByteArray(32) { id.hashCode().toByte() }),
            name = "Room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true, lastMessageDate = NOW,
        ).also { store.saveRemoteNodeSessionDTO(it) }
    }

    private fun message(contactID: UUID? = null, channelIndex: UByte? = null) = MessageDTO(
        radioId = radio, contactID = contactID, channelIndex = channelIndex, text = "hello",
        timestamp = 1_700_000_000u, createdAt = NOW, direction = MessageDirection.OUTGOING,
        status = MessageStatus.FAILED,
    )

    private fun roomMessage(room: RemoteNodeSessionDTO) = RoomMessageDTO(
        sessionID = room.id, authorKeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 1),
        text = "failed room send", timestamp = 1_700_000_000u, createdAt = NOW,
        isFromSelf = true, statusRawValue = MessageStatus.FAILED.rawValue,
    )

    private companion object {
        val NOW: Instant = Instant.ofEpochSecond(1_700_000_000)
    }
}
