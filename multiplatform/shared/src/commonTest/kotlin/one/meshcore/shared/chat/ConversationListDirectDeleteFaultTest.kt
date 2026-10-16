package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct conversation deletes. A store failure restores the row and reports it.
 * Cancellation and a thrown error leave the row hidden and keep the thrown instance.
 */
class ConversationListDirectDeleteFaultTest {
    @Test
    fun aCancelledClearRethrowsTheSameExceptionAndLeavesTheRowHidden() = runTest {
        val cancelled = CancellationException("stopped")
        val harness = directDeleteHarness(cancelled)

        val thrown = assertFailsWith<CancellationException> {
            harness.vm.deleteConversation(harness.conversation)
        }

        assertSame(cancelled, thrown)
        assertNull(harness.vm.state.value.errorMessage)
        assertTrue(harness.vm.state.value.snapshot.all.none { it.id == harness.contactId })
        assertTrue(harness.contactId in harness.vm.state.value.pendingRemovalIds)
        assertTrue(harness.store.clearedDirects.isEmpty())
    }

    @Test
    fun aClearErrorRethrowsTheSameErrorAndLeavesTheRowHidden() = runTest {
        val probe = DirectDeleteProbeError()
        val harness = directDeleteHarness(probe)

        val thrown = assertFailsWith<DirectDeleteProbeError> {
            harness.vm.deleteConversation(harness.conversation)
        }

        assertSame(probe, thrown)
        assertNull(harness.vm.state.value.errorMessage)
        assertTrue(harness.vm.state.value.snapshot.all.none { it.id == harness.contactId })
        assertTrue(harness.contactId in harness.vm.state.value.pendingRemovalIds)
        assertTrue(harness.store.clearedDirects.isEmpty())
    }

    @Test
    fun aStoreExceptionRestoresTheRowAndReportsTheFailure() = runTest {
        val messages = ChatErrorMessages.developerEnglish()
        val harness = directDeleteHarness(DirectDeleteStoreException("datastore down"))

        val deleted = harness.vm.deleteConversation(harness.conversation)

        assertFalse(deleted)
        assertEquals(messages.deleteConversationFailed, harness.vm.state.value.errorMessage)
        assertTrue(harness.vm.state.value.snapshot.all.any { it.id == harness.contactId })
        assertTrue(harness.vm.state.value.pendingRemovalIds.isEmpty())
        assertTrue(harness.store.clearedDirects.isEmpty())
    }
}

private class DirectDeleteProbeError : Error("probe")

private class DirectDeleteStoreException(message: String) : Exception(message)

private class DirectDeleteHarness(
    val store: FakeConversationListStore,
    val vm: ConversationListViewModel,
    val conversation: Conversation.Direct,
    val contactId: Uuid,
)

private suspend fun directDeleteHarness(failure: Throwable): DirectDeleteHarness {
    val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
    val store = FakeConversationListStore().apply { contacts = listOf(contact) }
    val vm = conversationListViewModel(store)
    vm.reload()
    store.clearDirectFailure = failure
    return DirectDeleteHarness(store, vm, Conversation.Direct(contact), contact.id)
}
