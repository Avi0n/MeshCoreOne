// PortedFrom: MC1Tests/Views/Chats/ChatViewModelFailedSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: the App adapter owns visible-thread persistence; composer errors use native localized copy.
package com.meshcoreone.android.parity

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.composer.ComposerDiagnostics
import com.meshcoreone.android.feature.chats.composer.ComposerEnvironment
import com.meshcoreone.android.feature.chats.composer.ComposerLocationSource
import com.meshcoreone.android.feature.chats.composer.ComposerSendFailure
import com.meshcoreone.android.feature.chats.composer.ComposerSendPhase
import com.meshcoreone.android.feature.chats.composer.ComposerSendPort
import com.meshcoreone.android.feature.chats.composer.ComposerStateHolder
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.composer.ComposerTimer
import com.meshcoreone.android.feature.chats.composer.ui.ComposerRoute
import com.meshcoreone.android.feature.chats.list.ChatListStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineEvent
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FailedSendThreadRecoveryOriginalTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val radio = RadioId(UUID.randomUUID())
    private var contactSequence = 0L

    @OriginalCase("ChatViewModelFailedSendTests::loadChannelMessages marks failed sends seen()")
    @Test fun channelOpenClearsTheStoredFailureBadgeWithoutChangingFailedStatus() = runTest {
        withRig {
            val channel = channel(3)
            val message = saveMessage(channelIndex = channel.index)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(channel.id))
            val conversation = TimelineConversation.Channel(channel)
            val viewModel = ChatViewModel(conversation, feature.timeline(conversation), test.backgroundScope)

            viewModel.open()
            holder.refreshFailedSendIndicators()

            assertTrue(viewModel.state.value.hasLoadedOnce)
            assertEquals(listOf(message.id), viewModel.state.value.messages.map { it.id })
            assertTrue(saved(message).failureSeen)
            assertEquals(MessageStatus.FAILED, saved(message).status)
            assertFalse(holder.state.value.conversationHasFailedSend(channel.id))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::handle messageFailed for the current contact marks seen()")
    @Test fun serviceFailureForTheVisibleContactClearsItsPersistedBadge() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id)
            harness.container.navigation.navigateToChat(contact)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
            val version = harness.appState.conversationsVersion.value
            val conversation = TimelineConversation.Direct(contact)

            assertEquals(TimelineEvent.MessageChanged(conversation.id, message.id), failure(conversation, message))
            holder.refreshFailedSendIndicators()

            assertTrue(saved(message).failureSeen)
            assertEquals(MessageStatus.FAILED, saved(message).status)
            assertFalse(holder.state.value.conversationHasFailedSend(contact.id))
            assertTrue(harness.appState.conversationsVersion.value > version)
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::handle messageFailed for another contact does not mark seen()")
    @Test fun foreignContactFailureCannotBeMarkedSeenByTheVisibleThread() = runTest {
        withRig {
            val current = contact()
            val other = contact()
            val sentinel = saveMessage(contactID = current.id, status = MessageStatus.PENDING)
            val failed = saveMessage(contactID = other.id)
            harness.container.navigation.navigateToChat(current)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(other.id))
            val conversation = TimelineConversation.Direct(current)

            failureThenSentinel(conversation, failed, sentinel)
            holder.refreshFailedSendIndicators()

            assertFalse(saved(failed).failureSeen)
            assertEquals(MessageStatus.FAILED, saved(failed).status)
            assertTrue(holder.state.value.conversationHasFailedSend(other.id))
        }
    }

    @OriginalCase("ChatViewModelFailedSendTests::recordLocalEnqueueFailure marks the current contact seen()")
    @Test fun offlineLocalEnqueueKeepsFailedSeenStorageAndAnActualLocalizedComposerError() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id, status = MessageStatus.PENDING)
            harness.container.navigation.navigateToChat(contact)
            val target = ComposerTarget.Direct(contact)
            val native = feature.sender(radio)
            val recorded = CompletableDeferred<Unit>()
            val sendPort = object : ComposerSendPort by native {
                // The original starts with an already-persisted pending row, then injects NotConnected.
                override suspend fun createPendingDirect(
                    text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?,
                ): MessageDTO {
                    assertEquals(message.text, text)
                    assertEquals(message.contactID, contact.id)
                    assertEquals(TextType.PLAIN, textType)
                    assertEquals(null, replyToId)
                    return saved(message)
                }
                override suspend fun recordLocalEnqueueFailure(messageId: UUID) {
                    assertEquals(message.id, messageId)
                    native.recordLocalEnqueueFailure(messageId)
                    recorded.complete(Unit)
                }
            }
            val environment = object : ComposerEnvironment by feature.environment(target, test.backgroundScope, NoLocation) {
                // Allows Send before the real offline queue rejects it; no session/radio is fabricated.
                override val connectionState = MutableStateFlow(DeviceConnectionState.READY)
            }
            val failures = mutableListOf<Throwable>()
            val composer = ComposerStateHolder(
                target, sendPort, feature.drafts, environment, test.backgroundScope,
                ComposerTimer { delay(it) }, ComposerDiagnostics { _, failure -> failures += failure },
            )
            val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                host.windowFocusChanged(true)
                composer.state.first { it.isConnected }
                composer.onDraftChanged(message.text)
                assertTrue(composer.send())
                val failed = composer.state.first { it.sendPhase is ComposerSendPhase.Failed }
                val phase = assertIs<ComposerSendPhase.Failed>(failed.sendPhase)
                recorded.await()
                holder.refreshFailedSendIndicators()

                assertEquals(ComposerSendFailure.NOT_CONNECTED, phase.reason)
                assertTrue(phase.messageSaved)
                assertEquals(ChatSendQueueServiceError.NotConnected,
                    assertIs<ChatSendQueueServiceException>(failures.single()).error)
                assertFalse(holder.state.value.conversationHasFailedSend(contact.id))
                assertEquals(MessageStatus.FAILED, saved(message).status)
                assertTrue(saved(message).failureSeen)
                host.get().setContent {
                    MeshCoreTheme {
                        ComposerRoute(composer, "Message", onPickContact = {})
                    }
                }
                compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorNoDeviceConnected))
                    .assertIsDisplayed()
            } finally {
                composer.close()
                host.pause().stop().destroy()
            }
        }
    }

    @Test fun serviceFailureForVisibleChannelMarksOnlyItsRadioAndSlotSeen() = runTest {
        withRig {
            val channel = channel(3)
            val other = channel(4)
            val message = saveMessage(channelIndex = channel.index)
            val otherMessage = saveMessage(channelIndex = other.index)
            val otherRadio = RadioId(UUID.randomUUID())
            val foreign = message.copy(id = UUID.randomUUID(), radioId = otherRadio)
            store.saveChannel(channel.copy(id = UUID.randomUUID(), radioId = otherRadio))
            store.saveMessage(foreign)
            harness.container.navigation.navigateToChannel(channel)

            failure(TimelineConversation.Channel(channel), message)
            holder.refreshFailedSendIndicators()

            assertTrue(saved(message).failureSeen)
            assertFalse(saved(otherMessage).failureSeen)
            assertFalse(saved(foreign).failureSeen)
            assertFalse(holder.state.value.conversationHasFailedSend(channel.id))
            assertTrue(holder.state.value.conversationHasFailedSend(other.id))
        }
    }

    @Test fun retainedCollectorDoesNotMarkFailuresSeenAfterLeavingTheChatsTab() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id)
            harness.container.navigation.navigateToChat(contact)
            harness.container.navigation.selectTab(AppTab.NODES)

            failure(TimelineConversation.Direct(contact), message)

            assertFalse(saved(message).failureSeen)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(contact.id))
        }
    }

    @Test fun retainedCollectorDoesNotMarkThePreviousConversationSeen() = runTest {
        withRig {
            val previous = contact()
            val current = contact()
            val message = saveMessage(contactID = previous.id)
            harness.container.navigation.navigateToChat(previous)
            harness.container.navigation.navigateToChat(current)

            failure(TimelineConversation.Direct(previous), message)

            assertFalse(saved(message).failureSeen)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.conversationHasFailedSend(previous.id))
        }
    }

    @Test fun failedSeenWriteIsReportedWithoutDroppingTheTimelineEvent() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id)
            harness.container.navigation.navigateToChat(contact)
            val conversation = TimelineConversation.Direct(contact)
            val logs = mutableListOf<LogRecord>()
            val logger = Logger.getLogger("com.mc1.AppChatsFeature")
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { logs += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            logger.addHandler(handler)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_seen BEFORE UPDATE OF failureSeen ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'seen fixture'); END",
            )
            try {
                assertEquals(TimelineEvent.MessageChanged(conversation.id, message.id), failure(conversation, message))
                assertFalse(saved(message).failureSeen)
                val reported = logs.single { it.message == "Mark visible failed send seen" }
                assertIs<PersistenceStoreException>(reported.thrown)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_seen")
                logger.removeHandler(handler)
            }
            failure(conversation, message)
            assertTrue(saved(message).failureSeen)
        }
    }

    @Test fun lateLocalFailureAndRepeatedEventsCannotDowngradeDeliveredStorage() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id, status = MessageStatus.DELIVERED)
            harness.container.navigation.navigateToChat(contact)

            feature.sender(radio).recordLocalEnqueueFailure(message.id)
            repeat(2) { failure(TimelineConversation.Direct(contact), message) }

            assertEquals(MessageStatus.DELIVERED, saved(message).status)
            assertFalse(saved(message).failureSeen)
            holder.refreshFailedSendIndicators()
            assertTrue(holder.state.value.failedSendIds.isEmpty())
        }
    }

    @Test fun localFailureWithALiveCollectorHasOneSeenTransitionAndTwoRequiredRefreshes() = runTest {
        withRig {
            val contact = contact()
            val message = saveMessage(contactID = contact.id, status = MessageStatus.PENDING)
            harness.container.navigation.navigateToChat(contact)
            val conversation = TimelineConversation.Direct(contact)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE seen_audit (transitions INTEGER NOT NULL)")
            sql.execSQL("INSERT INTO seen_audit VALUES (0)")
            sql.execSQL(
                "CREATE TRIGGER audit_seen AFTER UPDATE OF failureSeen ON messages " +
                    "WHEN NEW.failureSeen = 1 AND OLD.failureSeen = 0 " +
                    "BEGIN UPDATE seen_audit SET transitions = transitions + 1; END",
            )
            try {
                val before = harness.appState.conversationsVersion.value
                val subscribersBefore = harness.appState.messageEventStream.subscriberCount
                val next = test.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    feature.timeline(conversation).events.first()
                }
                assertEquals(subscribersBefore + 1, harness.appState.messageEventStream.subscriberCount)

                feature.sender(radio).recordLocalEnqueueFailure(message.id)

                assertEquals(TimelineEvent.MessageChanged(conversation.id, message.id), next.await())
                assertEquals(subscribersBefore, harness.appState.messageEventStream.subscriberCount)
                assertEquals(before + 2, harness.appState.conversationsVersion.value)
                assertTrue(saved(message).failureSeen)
                assertEquals(MessageStatus.FAILED, saved(message).status)
                sql.query("SELECT transitions FROM seen_audit").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
                holder.refreshFailedSendIndicators()
                assertTrue(holder.state.value.failedSendIds.isEmpty())
            } finally {
                sql.execSQL("DROP TRIGGER audit_seen")
                sql.execSQL("DROP TABLE seen_audit")
            }
        }
    }

    private inner class Rig(val test: TestScope, val harness: ContainerHarness) {
        val feature get() = harness.container.chatsFeature
        val holder = ChatListStateHolder(feature.listFor(radio), test.backgroundScope)

        suspend fun failure(conversation: TimelineConversation, message: MessageDTO): TimelineEvent {
            val next = test.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                feature.timeline(conversation).events.first()
            }
            harness.appState.messageEventStream.send(MessageEvent.MessageFailed(message.id))
            return next.await()
        }

        suspend fun failureThenSentinel(
            conversation: TimelineConversation, message: MessageDTO, sentinel: MessageDTO,
        ) {
            val next = test.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                feature.timeline(conversation).events.first()
            }
            harness.appState.messageEventStream.send(MessageEvent.MessageFailed(message.id))
            harness.appState.messageEventStream.send(MessageEvent.MessageStatusResolved(sentinel.id, sentinel.status))
            assertEquals(TimelineEvent.MessageChanged(conversation.id, sentinel.id), next.await())
        }
    }

    private suspend fun TestScope.withRig(body: suspend Rig.() -> Unit) {
        val harness = ContainerHarness(this, store)
        try {
            Rig(this, harness).body()
            assertTrue(harness.links.radios.isEmpty(), "Failed-send bookkeeping must not connect to a radio")
        } finally {
            harness.container.close()
        }
    }

    private suspend fun contact(): ContactDTO {
        val id = UUID(0, ++contactSequence)
        return ContactDTO(
            id = id, radioId = radio, publicKey = Bytes(ByteArray(32) { id.hashCode().toByte() }),
            name = "Test", typeRawValue = ContactType.CHAT.rawValue, outPathLength = 0u,
            lastHeardTimestamp = null, lastMessageDate = NOW,
        ).also { store.saveContact(it) }
    }

    private suspend fun channel(index: Int) = ChannelDTO(
        radioId = radio, index = index.toUByte(), name = "General", secret = Bytes.EMPTY, lastMessageDate = NOW,
    ).also { store.saveChannel(it) }

    private suspend fun saveMessage(
        contactID: UUID? = null, channelIndex: UByte? = null, status: MessageStatus = MessageStatus.FAILED,
    ) = MessageDTO(
        radioId = radio, contactID = contactID, channelIndex = channelIndex, text = "hello",
        timestamp = 1_700_000_000u, createdAt = NOW, direction = MessageDirection.OUTGOING, status = status,
    ).also { store.saveMessage(it) }

    private suspend fun saved(message: MessageDTO) =
        assertNotNull(store.fetchMessage(EntityKey(message.radioId, message.id)))

    private object NoLocation : ComposerLocationSource {
        override val isAuthorized = false
        override val currentCoordinate: Coordinate? = null
        override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = null
    }

    private companion object {
        val NOW: Instant = Instant.ofEpochSecond(1_700_000_000)
    }
}
