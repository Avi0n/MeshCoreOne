package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ChannelPreviewKey
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.FailedSendConversationKeys
import one.meshcore.shared.chat.MessageStatusEvent
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationListEventOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementServiceRebindsStatusEvents() {
        val store = CountingConversationListStore()
        val firstEvents = MutableSharedFlow<MessageStatusEvent>(extraBufferCapacity = 1)
        val replacementEvents = MutableSharedFlow<MessageStatusEvent>(extraBufferCapacity = 1)
        val eventSource = mutableStateOf(firstEvents)
        val serviceOwner = mutableStateOf(contactService())
        val inputs = ConversationListInputs(
            store = { store },
            contactService = { serviceOwner.value },
            radioId = { RADIO_ID },
            connectionState = MutableStateFlow(DeviceConnectionState.READY),
            onConversationTap = {},
            statusEvents = { eventSource.value },
            roomEvents = { emptyFlow() },
        )

        compose.setContent {
            TimestampFormatting {
                MaterialTheme { ConversationListRoot(inputs) }
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { firstEvents.subscriptionCount.value == 1 }

        compose.runOnIdle {
            eventSource.value = replacementEvents
            serviceOwner.value = contactService()
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            replacementEvents.subscriptionCount.value == 1 && firstEvents.subscriptionCount.value == 0
        }

        val fetchesBeforeEvent = store.failedSendFetches
        runBlocking { replacementEvents.emit(MessageStatusEvent.Failed(MESSAGE_ID)) }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { store.failedSendFetches > fetchesBeforeEvent }
        assertTrue(store.failedSendFetches > fetchesBeforeEvent)
    }

    private fun contactService(): ContactService =
        ContactService(NoOpContactSession(), InMemoryContactStore(), now = { 0L })

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val MESSAGE_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
    }
}

private class CountingConversationListStore : ConversationListStore {
    var failedSendFetches: Int = 0
        private set

    override suspend fun fetchConversationContacts(radioID: Uuid): List<ContactDto> = emptyList()

    override suspend fun fetchContactIDs(radioID: Uuid): Set<Uuid> = emptySet()

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = emptyList()

    override suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto> = emptyList()

    override suspend fun fetchFailedSendConversationKeys(radioID: Uuid): FailedSendConversationKeys {
        failedSendFetches += 1
        return FailedSendConversationKeys()
    }

    override suspend fun fetchLastContactMessages(contactIDs: List<Uuid>, limit: Int): Map<Uuid, List<MessageDto>> =
        emptyMap()

    override suspend fun fetchLastChannelMessages(
        channels: List<ChannelPreviewKey>,
        limit: Int,
    ): Map<Uuid, List<MessageDto>> = emptyMap()

    override suspend fun setContactMuted(contactID: Uuid, isMuted: Boolean) = Unit

    override suspend fun setChannelNotificationLevel(channelID: Uuid, level: NotificationLevel) = Unit

    override suspend fun setSessionNotificationLevel(sessionID: Uuid, level: NotificationLevel) = Unit

    override suspend fun setContactPinned(contactID: Uuid, isPinned: Boolean) = Unit

    override suspend fun setChannelPinned(channelID: Uuid, isPinned: Boolean) = Unit

    override suspend fun setSessionPinned(sessionID: Uuid, isPinned: Boolean) = Unit

    override suspend fun clearDirectConversation(contactID: Uuid) = Unit

    override suspend fun leaveRoom(session: RemoteNodeSessionDto) = Unit
}
