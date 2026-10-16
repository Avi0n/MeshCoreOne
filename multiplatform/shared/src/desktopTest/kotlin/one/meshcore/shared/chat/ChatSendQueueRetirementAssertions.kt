package one.meshcore.shared.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.MessageStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object ChatSendQueueRetirementAssertions {
    fun withFixture(
        channel: Boolean,
        retry: Boolean = false,
        sent: Boolean = false,
        block: suspend (ChatSendQueueRetirementFixture) -> Unit,
    ) = runBlocking {
        val fixture = ChatSendQueueRetirementFixture()
        try {
            withTimeout(ChatSendQueueRetirementFixture.TIMEOUT_MS) {
                fixture.initialize(channel, retry, sent)
                block(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    suspend fun assertParked(
        fixture: ChatSendQueueRetirementFixture,
        label: String,
        status: MessageStatus = MessageStatus.PENDING,
    ): ChatSendQueueRetirementFixture.Snapshot {
        val snapshot = fixture.snapshot(label)
        assertEquals(0, snapshot.oldWrites, "a retired session must not send")
        assertEquals(fixture.message.id, snapshot.message.id)
        assertEquals(ChatSendQueueRetirementFixture.RADIO_A, snapshot.message.radioID)
        assertEquals(ChatSendQueueRetirementFixture.TEST_TEXT, snapshot.message.text)
        assertEquals(status, snapshot.message.status)
        val row = snapshot.pending.single()
        assertEquals(fixture.message.id, row.messageID)
        assertEquals(ChatSendQueueRetirementFixture.RADIO_A, row.radioID)
        assertEquals(0, row.attemptCount, "retirement must not start preflight")
        assertForeignUnchanged(fixture, snapshot)
        return snapshot
    }

    suspend fun assertFreshReplay(fixture: ChatSendQueueRetirementFixture) {
        assertEquals(1, fixture.replayFresh(), "two hydrate calls must replay the accepted intent exactly once")
        val after = fixture.snapshot("fresh-owner-replayed")
        assertEquals(0, after.oldWrites)
        assertTrue(after.pending.isEmpty())
        assertForeignUnchanged(fixture, after)
    }

    fun assertForeignUnchanged(
        fixture: ChatSendQueueRetirementFixture,
        snapshot: ChatSendQueueRetirementFixture.Snapshot,
    ) {
        val (message, pending) = fixture.foreignBaseline()
        assertEquals(message, snapshot.foreignMessage)
        assertEquals(pending, snapshot.foreignPending)
    }
}
