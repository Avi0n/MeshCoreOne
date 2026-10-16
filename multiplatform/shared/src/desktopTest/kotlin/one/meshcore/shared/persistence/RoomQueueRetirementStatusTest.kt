package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChannelMessageEnvelope
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.PendingSendIds
import one.meshcore.shared.chat.toPendingSendDto
import one.meshcore.shared.model.MessageStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomQueueRetirementStatusTest {
    @Test
    fun retirementOnlySettlesActualRetryingAndPreservesEveryOtherStoredState() = withStore { store ->
        for (status in MessageStatus.entries) {
            val message = ChatFixtures.message(status = status)
            store.saveMessage(message)
            val original = checkNotNull(store.fetchMessage(message.id))
            val moved = store.clearRetryingToSent(message.id, onlyRetrying = true)
            assertEquals(status == MessageStatus.RETRYING, moved)
            val expected = if (status == MessageStatus.RETRYING) {
                original.copy(status = MessageStatus.SENT)
            } else {
                original
            }
            assertEquals(expected, store.fetchMessage(message.id), "retirement from $status")
        }
    }

    @Test
    fun retirementCannotConvertAManualRetryReplacementBackToSent() = withStore { store ->
        val message = ChatFixtures.message(status = MessageStatus.RETRYING, channelIndex = CHANNEL_INDEX)
        store.saveMessage(message)
        val envelope = ChannelMessageEnvelope(message.id, CHANNEL_INDEX)
        val dto = envelope.toPendingSendDto(PendingSendIds().next(), message.radioID, 1, FIXED_NOW_SECONDS, 0)
        store.replacePendingSendForRetry(dto)
        val accepted = checkNotNull(store.fetchMessage(message.id))
        assertEquals(MessageStatus.PENDING, accepted.status)
        assertFalse(store.clearRetryingToSent(message.id, onlyRetrying = true))
        assertEquals(accepted, store.fetchMessage(message.id))
        assertTrue(store.clearRetryingToSent(message.id), "ordinary retry-budget policy is unchanged")
        assertEquals(MessageStatus.SENT, store.fetchMessage(message.id)?.status)
    }

    private fun withStore(block: suspend (RoomChatStore) -> Unit) = runBlocking {
        val file = File.createTempFile("queue-retirement-status", ".db").also { it.delete() }
        val database = meshDatabase(file.absolutePath)
        try {
            val store = RoomChatStore(
                ChatFixtures.RADIO,
                database.chatDaos(),
                RxLogCorrelator(database.rxLogEntryDao()),
            )
            block(store)
        } finally {
            database.close()
            file.delete()
        }
    }

    private companion object {
        const val CHANNEL_INDEX = 1
        const val FIXED_NOW_SECONDS = 1_700_000_000.0
    }
}
