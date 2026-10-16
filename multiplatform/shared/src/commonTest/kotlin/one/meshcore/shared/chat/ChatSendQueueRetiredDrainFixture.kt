package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

internal class ChatSendQueueRetiredDrainFixture(
    scope: CoroutineScope,
    private val channel: Boolean,
    retrying: Boolean = false,
    wrapped: Boolean = false,
) {
    val store = FakeSendQueueStore()
    private val heldSession = HeldSession(retrying, wrapped)
    val entered get() = heldSession.entered
    val release get() = heldSession.release
    val cancellation get() = heldSession.cancellation
    val sessionCalls get() = heldSession.calls
    val completedSessionCalls get() = heldSession.completedCalls
    val service = MessageService(
        heldSession,
        store.messages,
        scope,
        MessageServiceIdentity({ TEST_NOW_SECONDS }, SequentialIds()::next),
        MessageServiceConfig(maxAttempts = RETRY_ATTEMPTS),
    )
    val queue = ChatSendQueueService(
        ChatFixtures.RADIO,
        ChatSendQueueCollaborators(
            store,
            service,
            FakeChannelResolver(),
            scope,
            { TEST_NOW_SECONDS },
            PendingSendIds()::next,
        ),
    )
    lateinit var events: ReceiveChannel<MessageStatusEvent>
        private set
    lateinit var original: MessageDto
        private set

    suspend fun initialize(status: MessageStatus) {
        events = service.statusEvents()
        val contact = ChatFixtures.directContact()
        store.contacts[contact.id] = contact
        store.messages.seedChannel(ChatFixtures.RADIO, CHANNEL_INDEX, CHANNEL_ID)
        original = ChatFixtures.message(
            text = TEST_TEXT,
            status = status,
            contactID = contact.id.takeUnless { channel },
            channelIndex = CHANNEL_INDEX.takeIf { channel },
        )
        store.messages.saveMessage(original)
    }

    suspend fun enqueue(resend: Boolean = false) {
        if (channel) {
            queue.enqueueChannel(
                ChannelMessageEnvelope(original.id, CHANNEL_INDEX, resend, original.text, original.timestamp),
            )
        } else {
            queue.enqueueDM(DirectMessageEnvelope(original.id, ChatFixtures.CONTACT, resend))
        }
    }

    private class HeldSession(
        private val retrying: Boolean,
        private val wrapped: Boolean,
        private val delegate: FakeChatSession = FakeChatSession(),
    ) : ChatSession by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<CancellationException>()
        var calls = 0
            private set
        var completedCalls = 0
            private set

        override suspend fun sendDirectMessage(
            destination: ByteArray,
            text: String,
            timestamp: Long,
            attempt: Int,
        ): MessageSentInfo {
            calls += 1
            if (retrying && attempt == 0) {
                completedCalls += 1
                return delegate.sentInfo
            }
            waitForRelease()
            completedCalls += 1
            return delegate.sentInfo
        }

        override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long) {
            calls += 1
            waitForRelease()
            completedCalls += 1
        }

        override suspend fun sendChannelMessage(
            channelIndex: Int,
            text: String,
            timestamp: Long,
            floodScope: ResolvedFloodScope,
        ) = sendChannelMessage(channelIndex, text, timestamp)

        private suspend fun waitForRelease() {
            entered.complete(Unit)
            try {
                release.await()
            } catch (stopped: CancellationException) {
                val propagated = if (wrapped) {
                    CancellationException(WRAPPED_REASON, stopped)
                } else {
                    stopped
                }
                cancellation.complete(propagated)
                throw propagated
            }
        }
    }

    companion object {
        const val RETRY_ATTEMPTS = 2
        private const val CHANNEL_INDEX = 1
        private const val TEST_TEXT = "active drain retirement fixture"
        private const val WRAPPED_REASON = "fixture session cancellation wrapper"
        private val CHANNEL_ID = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
    }
}
