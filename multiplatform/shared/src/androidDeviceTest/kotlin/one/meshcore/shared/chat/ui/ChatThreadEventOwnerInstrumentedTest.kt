package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.chat.ChatCoordinator
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ChatSession
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.InMemoryMessageStore
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.MessageService
import one.meshcore.shared.chat.MessageServiceIdentity
import one.meshcore.shared.chat.MessageWindow
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.ui.coordinatorChatScroll
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration

@RunWith(AndroidJUnit4::class)
class ChatThreadEventOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun replacementServiceRebindsTheOpenThreadEventCollector() {
        val store = OwnerThreadStore()
        val registry = ChatCoordinatorRegistry(store, scope)
        val draftStore = runBlocking { DraftStore.create(InMemoryPreferencesStore()) }
        val draftEditors = ChatDraftEditors(draftStore, scope)
        val firstEvents = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 1)
        val replacementEvents = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 1)
        val eventSource = mutableStateOf(firstEvents)
        val serviceOwner = mutableStateOf(messageService())
        val connectionState = kotlinx.coroutines.flow.MutableStateFlow(DeviceConnectionState.READY)
        val contact = contact()
        val environment = ChatThreadEnvironment(
            graph = ChatThreadGraphInputs(
                registry = { registry },
                store = { store },
                sendQueue = { null },
                messageService = { serviceOwner.value },
                draftEditors = { draftEditors },
                teardownScope = { scope },
                currentContact = { contact },
                unreadCount = { 0 },
                localNodeName = { LOCAL_NODE_NAME },
                incomingEvents = { eventSource.value },
                statusEvents = { emptyFlow() },
            ),
        )

        val pendingScroll = coordinatorChatScroll(NavigationCoordinator(scope))
        compose.setContent {
            TimestampFormatting {
                CompositionLocalProvider(
                    LocalPendingChatScroll provides pendingScroll,
                ) {
                    MaterialTheme {
                        ChatThreadRoot(
                            conversationID = CONVERSATION_ID,
                            title = THREAD_TITLE,
                            environment = environment,
                            connectionState = connectionState,
                            onBack = {},
                            contact = contact,
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { firstEvents.subscriptionCount.value == 1 }

        compose.runOnIdle {
            eventSource.value = replacementEvents
            serviceOwner.value = messageService()
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            replacementEvents.subscriptionCount.value == 1 && firstEvents.subscriptionCount.value == 0
        }

        runBlocking {
            firstEvents.emit(IncomingMessageEvent.DirectMessageReceived(message(RETIRED_TEXT), CONTACT_ID))
            replacementEvents.emit(IncomingMessageEvent.DirectMessageReceived(message(REPLACEMENT_TEXT), CONTACT_ID))
        }

        compose.onNodeWithText(REPLACEMENT_TEXT).assertExists()
        compose.onNodeWithText(RETIRED_TEXT).assertDoesNotExist()
    }

    @Test
    fun anOlderNotificationTargetRendersAndScrollsIntoView() {
        val rows = notificationRows()
        val navigation = NavigationCoordinator(scope)
        navigation.navigateToChat(CONTACT_ID.value, rows.first().id.value)
        mountNotificationThread(rows, navigation)
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { navigation.pendingScrollTarget.value == null }
        compose.onNodeWithText(rows.first().text).assertIsDisplayed()
    }

    @Test
    fun notificationTapsScrollWhileTheSameThreadRemainsMounted() {
        val rows = notificationRows()
        val navigation = NavigationCoordinator(scope)
        mountNotificationThread(rows, navigation)
        compose.onNodeWithText(rows.last().text).assertIsDisplayed()
        val first = rows.first()
        compose.runOnIdle { navigation.navigateToChat(CONTACT_ID.value, first.id.value) }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { navigation.pendingScrollTarget.value == null }
        compose.onNodeWithText(first.text).assertIsDisplayed()
        val second = rows[ChatCoordinator.PAGE_SIZE]
        compose.runOnIdle { navigation.navigateToChat(CONTACT_ID.value, second.id.value) }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { navigation.pendingScrollTarget.value == null }
        compose.onNodeWithText(second.text).assertIsDisplayed()
        compose.runOnIdle { navigation.navigateToChat(CONTACT_ID.value, first.id.value) }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { navigation.pendingScrollTarget.value == null }
        compose.onNodeWithText(first.text).assertIsDisplayed()
    }

    private fun notificationRows(): List<MessageDto> = (0 until ChatCoordinator.PAGE_SIZE * 2 + 5).map { index ->
        message("notification row $index").copy(timestamp = 1_700_000_000L + index, createdAt = 1_700_000_000.0 + index)
    }

    private fun mountNotificationThread(rows: List<MessageDto>, navigation: NavigationCoordinator) {
        val store = OwnerThreadStore(rows)
        val registry = ChatCoordinatorRegistry(store, scope)
        val drafts = runBlocking { DraftStore.create(InMemoryPreferencesStore()) }
        val draftEditors = ChatDraftEditors(drafts, scope)
        val contact = contact()
        val pending = coordinatorChatScroll(
            navigation,
        ).forConversation(ChatRoute.Direct(CONTACT_ID.value).conversationId)
        val environment = ChatThreadEnvironment(
            graph = ChatThreadGraphInputs(
                registry = { registry },
                store = { store },
                sendQueue = { null },
                messageService = { null },
                draftEditors = { draftEditors },
                teardownScope = { scope },
                currentContact = { contact },
                unreadCount = { 0 },
                localNodeName = { LOCAL_NODE_NAME },
                incomingEvents = { emptyFlow() },
                statusEvents = { emptyFlow() },
            ),
        )
        val connection = kotlinx.coroutines.flow.MutableStateFlow(DeviceConnectionState.DISCONNECTED)
        compose.setContent {
            TimestampFormatting {
                CompositionLocalProvider(LocalPendingChatScroll provides pending) {
                    MaterialTheme {
                        ChatThreadRoot(
                            conversationID = CONVERSATION_ID,
                            title = THREAD_TITLE,
                            environment = environment,
                            connectionState = connection,
                            onBack = {},
                            contact = contact,
                        )
                    }
                }
            }
        }
    }

    private fun messageService(): MessageService = MessageService(
        session = IdleChatSession,
        store = InMemoryMessageStore(),
        scope = scope,
        identity = MessageServiceIdentity(now = { 1_700_000_000.0 }, newMessageId = Uuid::random),
    )

    private companion object {
        const val LOCAL_NODE_NAME = "Local"
        const val THREAD_TITLE = "Event owner"
        const val RETIRED_TEXT = "retired event"
        const val REPLACEMENT_TEXT = "replacement event"
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val CONTACT_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
        val CONVERSATION_ID = one.meshcore.shared.chat.ChatConversationID.dm(RADIO_ID, CONTACT_ID)

        fun contact(): ContactDto = ContactDto(
            id = CONTACT_ID,
            radioID = RADIO_ID,
            publicKey = ByteArray(32) { 1 },
            name = "Contact",
            typeRawValue = ContactType.CHAT.raw,
            flags = 0,
            outPathLength = 0,
            outPath = byteArrayOf(),
            lastAdvertTimestamp = 0,
            latitude = 0.0,
            longitude = 0.0,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = false,
            unreadCount = 0,
        )

        fun message(text: String): MessageDto = MessageDto(
            id = Uuid.random(),
            radioID = RADIO_ID,
            text = text,
            timestamp = 1_700_000_000,
            createdAt = 1_700_000_000.0,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.SENT,
            textType = TextType.PLAIN,
            pathLength = 0,
            isRead = false,
            heardRepeats = 0,
            sendCount = 0,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
            contactID = CONTACT_ID,
        )
    }
}

private class OwnerThreadStore(private val rows: List<MessageDto> = emptyList()) : ChatThreadStore {
    override suspend fun fetchMessage(id: Uuid): MessageDto? = rows.firstOrNull { it.id == id }

    override suspend fun fetchDirectMessages(contactID: Uuid, limit: Int, offset: Int): List<MessageDto> =
        page(limit, offset)

    override suspend fun fetchChannelMessages(
        radioID: Uuid,
        channelIndex: Int,
        limit: Int,
        offset: Int,
    ): List<MessageDto> = page(limit, offset)

    override suspend fun fetchMessageWindow(
        conversation: one.meshcore.shared.chat.ChatConversationID,
        anchorSortDate: Double?,
        floorLimit: Int,
    ): MessageWindow = MessageWindow(page(floorLimit, 0), hasMore = rows.size > floorLimit)

    private fun page(limit: Int, offset: Int): List<MessageDto> {
        val end = (rows.size - offset).coerceIn(0, rows.size)
        return rows.subList((end - limit).coerceAtLeast(0), end)
    }

    override suspend fun clearUnread(conversation: one.meshcore.shared.chat.ChatConversationID) = Unit

    override suspend fun deleteMessage(id: Uuid) = Unit

    override suspend fun recomputeContactLastMessageDate(contactID: Uuid) = Unit

    override suspend fun markMessageFailedUnlessDelivered(id: Uuid): Boolean = false
}

private object IdleChatSession : ChatSession {
    override val events: SharedFlow<MeshEvent> = MutableSharedFlow()

    override suspend fun currentSelfPublicKey(): ByteArray? = null

    override suspend fun sendDirectMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo = error("unused")

    override suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = error("unused")

    override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long) = error("unused")

    override suspend fun resetPath(publicKey: ByteArray) = error("unused")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("unused")

    override suspend fun waitForAcknowledgement(code: ByteArray, timeout: Duration): Boolean = error("unused")
}
