package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionSyncActivityAdmissionTest {
    @Test
    fun staleStartCannotRaiseCurrentRetryActivity() = exercise { f ->
        val current = f.replaceAndHoldCurrentRetry()
        val before = f.snapshot()
        f.manager.startSyncActivity(f.oldContainer)
        f.settleProjection()
        f.assertCurrentBeforeOriginalReturn(current)
        assertEquals(before, f.snapshot())
        assertEquals(1, f.ui.syncActivityCount.value)
        assertEquals(StatusPillState.Syncing, f.app.statusPill.value)
    }

    @Test
    fun staleEndCannotConsumeCurrentRetryActivity() = exercise { f ->
        val current = f.replaceAndHoldCurrentRetry()
        val before = f.snapshot()
        f.manager.endSyncActivity(f.oldContainer, succeeded = true)
        f.settleProjection()
        f.assertCurrentBeforeOriginalReturn(current)
        assertEquals(before, f.snapshot())
        assertEquals(1, f.ui.syncActivityCount.value)
        assertEquals(StatusPillState.Syncing, f.app.statusPill.value)
    }

    @Test
    fun retiredCallbacksLeaveActualDisconnectResetIdle() = exercise { f ->
        f.manager.disconnect()
        f.await { f.app.connectionUiServices == null && f.ui.syncActivityCount.value == 0 }
        val before = f.snapshot()
        f.manager.startSyncActivity(f.oldContainer)
        assertEquals(0, f.ui.syncActivityCount.value)
        f.manager.endSyncActivity(f.oldContainer, succeeded = true)
        assertEquals(0, f.ui.syncActivityCount.value)
        assertEquals(false, f.ui.showReadyToast.value)
        assertEquals(before, f.snapshot())
    }

    @Test
    fun currentNestedActivityRemainsHeldUntilActualRetryFinishes() = exercise { f ->
        val current = f.replaceAndHoldCurrentRetry()
        f.manager.startSyncActivity(current.container)
        assertEquals(2, f.ui.syncActivityCount.value)
        f.manager.endSyncActivity(current.container, succeeded = true)
        assertEquals(1, f.ui.syncActivityCount.value)
        f.settleProjection()
        assertEquals(StatusPillState.Syncing, f.app.statusPill.value)
        assertTrue(current.worker.isActive && current.operation.isActive)
        f.second.release.complete(Unit)
        current.worker.join()
        current.operation.join()
        f.settleProjection()
        assertEquals(ConnectionState.Ready(ConnectionSyncActivityFixture.B), f.manager.state.value)
        assertEquals(0, f.ui.syncActivityCount.value)
        assertEquals(StatusPillState.Ready, f.app.statusPill.value)
        val channel =
            requireNotNull(
                current.container.channelService.getChannel(current.radio, ConnectionSyncActivitySession.SLOT),
            )
        assertEquals("Synthetic retry", channel.name)
        assertEquals(ConnectionSyncActivitySession.SLOT, channel.index)
        assertTrue(channel.secret.contentEquals(ByteArray(16) { (it + 17).toByte() }))
    }

    private fun exercise(block: suspend (ConnectionSyncActivityFixture) -> Unit) {
        val f = ConnectionSyncActivityFixture()
        try {
            runBlocking(f.dispatcher) {
                withTimeout(ConnectionSyncActivityFixture.TIMEOUT_MS) {
                    f.start()
                    block(f)
                }
            }
        } finally {
            f.close()
        }
    }

    private suspend fun ConnectionSyncActivityFixture.assertCurrentBeforeOriginalReturn(
        current: ConnectionSyncActivityFixture.Current,
    ) {
        assertEquals(ConnectionState.Ready(ConnectionSyncActivityFixture.B), manager.state.value)
        assertEquals(current.container, manager.services)
        assertEquals(current.radio, manager.radioId)
        assertEquals(current.generation, manager.connectGeneration)
        assertTrue(current.worker.isActive && current.operation.isActive)
        assertEquals(false, second.forwarded)
        assertEquals(3, second.getChannelCallCount)
        assertEquals(0, first.closedBacklogAdmissions)
        assertTrue(first.sentMessages.isEmpty() && second.sentMessages.isEmpty())
    }
}
