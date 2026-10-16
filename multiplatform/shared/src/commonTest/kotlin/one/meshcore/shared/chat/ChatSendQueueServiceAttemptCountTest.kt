package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors the Swift `ChatSendQueueServiceAttemptCountTests`: the three distinguishable states of the
 * persistent `attemptCount` column and their `preserveTimestamp` consequence. Each drain is forced to fail
 * transiently so the row survives (parked) and its post-bump state is observable. The message timestamp is
 * pinned to an hour before "now" so a fresh stamp (preserveTimestamp = false) is distinguishable from a
 * preserved one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueServiceAttemptCountTest {
    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val FAST_BACKOFF = MessageServiceConfig(
            poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelaySeconds = 0.01),
        )
    }

    private class Harness(
        val store: FakeSendQueueStore,
        val queue: ChatSendQueueService,
        val session: FakeChatSession,
        val messageID: Uuid,
        val originalTimestamp: Long,
    )

    private suspend fun setup(scope: CoroutineScope, attemptCount: Int?): Harness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val messageService =
            MessageService(
                session,
                store.messages,
                scope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
                FAST_BACKOFF,
            )
        val contact = ChatFixtures.contact("Test Contact")
        store.contacts[contact.id] = contact
        val pending = messageService.createPendingMessage("Hello", contact)
        val past = NOW.toLong() - 3600
        store.messages.updateMessageTimestamp(pending.id, past)

        store.pending.upsertPendingSend(
            DirectMessageEnvelope(pending.id, contact.id)
                .toPendingSendDto(Uuid.normalized("EEEEEEEE-0000-0000-0000-000000000001"), RADIO, 1, NOW, attemptCount),
        )
        val queue = ChatSendQueueService(
            radioID = RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = store,
                messageService = messageService,
                channelResolver = FakeChannelResolver(),
                scope = scope,
                now = { NOW },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        // A transient send failure parks the row so its post-bump state stays observable.
        session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }
        return Harness(store, queue, session, pending.id, past)
    }

    private suspend fun rowFor(harness: Harness): PendingSendDto? =
        harness.store.fetchPendingSends(RADIO).firstOrNull { it.messageID == harness.messageID }

    @Test
    fun `fresh send with attemptCount 0 first drain uses a fresh timestamp`() = runTest {
        val harness = setup(backgroundScope, attemptCount = 0)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertEquals(1, rowFor(harness)?.attemptCount, "first drain bumps 0 to 1")
        assertEquals(
            NOW.toLong(),
            harness.store.messages.fetchMessage(harness.messageID)?.timestamp,
            "preserveTimestamp = false stamps a fresh wire timestamp before the send",
        )
        assertNotEquals(harness.originalTimestamp, harness.store.messages.fetchMessage(harness.messageID)?.timestamp)
    }

    @Test
    fun `process restart with attemptCount 1 rehydrates with preserveTimestamp true`() = runTest {
        val harness = setup(backgroundScope, attemptCount = 1)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertEquals(2, rowFor(harness)?.attemptCount, "rehydrate drain bumps 1 to 2")
        assertEquals(
            harness.originalTimestamp,
            harness.store.messages.fetchMessage(harness.messageID)?.timestamp,
            "preserveTimestamp = true reuses the original wire timestamp so mesh dedup catches a duplicate",
        )
    }

    @Test
    fun `bump failure parks the envelope, preserves the row, and does not send`() = runTest {
        val harness = setup(backgroundScope, attemptCount = 0)
        harness.store.pending.incrementFault = { RuntimeException("save failed") }

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertEquals(0, rowFor(harness)?.attemptCount, "a thrown bump must not advance attemptCount")
        assertEquals(
            harness.originalTimestamp,
            harness.store.messages.fetchMessage(harness.messageID)?.timestamp,
            "the park runs before any wire-affecting work, so the timestamp is untouched",
        )
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertEquals(0, harness.session.directSendAttempts.size, "no wire send happened")
    }
}
