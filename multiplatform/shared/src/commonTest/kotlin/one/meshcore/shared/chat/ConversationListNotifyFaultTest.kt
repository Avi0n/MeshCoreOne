package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Notification-level writes. A store failure rolls the row back.
 * Cancellation and a thrown error leave the optimistic level and keep the thrown instance.
 */
class ConversationListNotifyFaultTest {
    @Test
    fun aCancelledWriteRethrowsTheSameExceptionAndKeepsTheOptimisticLevel() = runTest {
        val cancelled = CancellationException("stopped")
        val harness = notifyHarness(cancelled)

        val thrown = assertFailsWith<CancellationException> {
            harness.vm.setNotificationLevel(harness.conversation, NotificationLevel.MUTED)
        }

        assertSame(cancelled, thrown)
        assertEquals(NotificationLevel.MUTED, harness.vm.state.value.channels.single().notificationLevel)
        assertTrue(harness.store.notificationLevelWrites.isEmpty())
    }

    @Test
    fun aWriteErrorRethrowsTheSameErrorAndKeepsTheOptimisticLevel() = runTest {
        val probe = NotificationLevelProbeError()
        val harness = notifyHarness(probe)

        val thrown = assertFailsWith<NotificationLevelProbeError> {
            harness.vm.setNotificationLevel(harness.conversation, NotificationLevel.MUTED)
        }

        assertSame(probe, thrown)
        assertEquals(NotificationLevel.MUTED, harness.vm.state.value.channels.single().notificationLevel)
        assertTrue(harness.store.notificationLevelWrites.isEmpty())
    }

    @Test
    fun aStoreExceptionRollsTheLevelBack() = runTest {
        val harness = notifyHarness(NotificationLevelStoreException("datastore down"))

        harness.vm.setNotificationLevel(harness.conversation, NotificationLevel.MUTED)

        assertEquals(NotificationLevel.ALL, harness.vm.state.value.channels.single().notificationLevel)
        assertTrue(harness.store.notificationLevelWrites.isEmpty())
    }
}

private class NotificationLevelProbeError : Error("probe")

private class NotificationLevelStoreException(message: String) : Exception(message)

private class NotifyHarness(
    val store: FakeConversationListStore,
    val vm: ConversationListViewModel,
    val conversation: Conversation.Channel,
)

private suspend fun notifyHarness(failure: Throwable): NotifyHarness {
    val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
    val store = FakeConversationListStore().apply { channels = listOf(channel) }
    val vm = conversationListViewModel(store)
    vm.reload()
    store.writeFailure = failure
    return NotifyHarness(store, vm, Conversation.Channel(channel))
}
