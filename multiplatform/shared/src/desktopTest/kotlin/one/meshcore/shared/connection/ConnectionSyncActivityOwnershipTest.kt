package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionSyncActivityOwnershipTest {
    @Test
    fun completedOldChannelPhaseCannotReleaseCurrentRetryPill() {
        val f = ConnectionSyncActivityFixture()
        try {
            runBlocking(f.dispatcher) {
                withTimeout(ConnectionSyncActivityFixture.TIMEOUT_MS) {
                    f.start()
                    val current = f.replaceAndHoldCurrentRetry()
                    val before = f.snapshot()
                    val prefs = f.preferences()
                    f.releaseOriginal()
                    f.assertCurrent(current)
                    assertEquals(before, f.snapshot())
                    assertEquals(prefs, f.preferences())
                    f.settleProjection()
                    assertEquals(
                        StatusPillState.Syncing,
                        f.app.statusPill.value,
                        "LAST: old channel completion released the current radio retry pill; count=${f.ui.syncActivityCount.value}, forwarded=${f.first.forwarded}, closedBacklog=${f.first.closedBacklogAdmissions}",
                    )
                    assertEquals(1, f.ui.syncActivityCount.value)
                }
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun liveCompletedChannelPhaseClosesItsActivityAndShowsReady() {
        val f = ConnectionSyncActivityFixture()
        try {
            runBlocking(f.dispatcher) {
                withTimeout(ConnectionSyncActivityFixture.TIMEOUT_MS) {
                    f.start()
                    val before = f.snapshot()
                    f.releaseOriginal().getOrThrow()
                    assertEquals(ConnectionState.Ready(ConnectionSyncActivityFixture.A), f.manager.state.value)
                    assertSame(f.oldContainer, f.manager.services)
                    assertEquals(before, f.snapshot())
                    f.await { f.app.connectionState.value == DeviceConnectionState.READY }
                    f.settleProjection()
                    assertEquals(StatusPillState.Ready, f.app.statusPill.value)
                    assertEquals(0, f.ui.syncActivityCount.value)
                    assertEquals(1, f.first.getMessageCount)
                    assertEquals(1, f.first.autoFetchStartCount)
                    assertTrue(f.first.sentMessages.isEmpty())
                }
            }
        } finally {
            f.close()
        }
    }
}
