package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.channel.FakeChannelSession
import one.meshcore.shared.channel.testChannelService
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Radio conversation deletes. A command failure keeps the row and reports it.
 * A timeout stays a retryable failure. Cancellation and a thrown error keep the thrown instance.
 */
class ConversationListRadioDeleteFaultTest {
    @Test
    fun aCancelledClearRethrowsTheSameExceptionAndKeepsTheRow() = runTest {
        val cancelled = CancellationException("stopped")
        val harness = radioDeleteHarness { throw cancelled }

        val thrown = assertFailsWith<CancellationException> {
            harness.vm.deleteConversation(harness.conversation)
        }

        // withTimeout copies a CancellationException thrown inside the radio action. The copy keeps
        // the original as its cause, and the delete rethrows that copy rather than a timeout.
        assertSame(cancelled, thrown.cause)
        assertTrue(thrown !is TimeoutCancellationException)
        assertRowKept(harness)
        assertNull(harness.vm.state.value.channelDeleteFailure)
        assertNull(harness.vm.state.value.errorMessage)
    }

    @Test
    fun aClearErrorRethrowsTheSameErrorAndKeepsTheRow() = runTest {
        val probe = RadioDeleteProbeError()
        val harness = radioDeleteHarness { throw probe }

        val thrown = assertFailsWith<RadioDeleteProbeError> {
            harness.vm.deleteConversation(harness.conversation)
        }

        assertSame(probe, thrown)
        assertRowKept(harness)
        assertNull(harness.vm.state.value.channelDeleteFailure)
        assertNull(harness.vm.state.value.errorMessage)
    }

    @Test
    fun aCommandExceptionKeepsTheRowAndReportsTheFailure() = runTest {
        val messages = ChatErrorMessages.developerEnglish()
        val harness = radioDeleteHarness { throw RadioDeleteStoreException("radio offline") }

        val deleted = harness.vm.deleteConversation(harness.conversation)

        assertFalse(deleted)
        assertRowKept(harness)
        assertEquals(messages.deleteConversationFailed, harness.vm.state.value.channelDeleteFailure?.message)
        assertEquals(harness.channelId, harness.vm.state.value.channelDeleteFailure?.channel?.id)
        assertNull(harness.vm.state.value.errorMessage)
    }
}

private const val RADIO_DELETE_SLOT = 3

private class RadioDeleteProbeError : Error("probe")

private class RadioDeleteStoreException(message: String) : Exception(message)

private class RadioDeleteHarness(
    val vm: ConversationListViewModel,
    val conversation: Conversation.Channel,
    val channelId: Uuid,
)

private fun assertRowKept(harness: RadioDeleteHarness) {
    assertTrue(harness.vm.state.value.snapshot.all.any { it.id == harness.channelId })
    assertFalse(harness.channelId in harness.vm.state.value.deletingIds)
}

private suspend fun radioDeleteHarness(onClearNotifications: suspend () -> Unit): RadioDeleteHarness {
    val channel = ChatFixtures.channel(name = "General", index = RADIO_DELETE_SLOT)
    val store = FakeConversationListStore().apply { channels = listOf(channel) }
    val vm = conversationListViewModel(
        store,
        hooks = ConversationListTestHooks(
            channelService = { testChannelService(session = FakeChannelSession()) },
            clearChannelNotifications = { _, _ -> onClearNotifications() },
        ),
    )
    vm.reload()
    return RadioDeleteHarness(vm, Conversation.Channel(channel), channel.id)
}
