package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors the iOS `StatusPillStateTests` and the priority-ladder subset of `ConnectionUIStateTests`:
 * `failed > syncing > ready > connecting > disconnected > hidden`.
 */
class StatusPillStateTest {
    private fun pill(
        syncFailedPillVisible: Boolean = false,
        syncActivityCount: Int = 0,
        connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
        showReadyToast: Boolean = false,
        disconnectedPillVisible: Boolean = false,
    ): StatusPillState = statusPillState(
        StatusPillInputs(
            syncFailedPillVisible = syncFailedPillVisible,
            syncActivityCount = syncActivityCount,
            connectionState = connectionState,
            showReadyToast = showReadyToast,
            disconnectedPillVisible = disconnectedPillVisible,
        ),
    )

    @Test
    fun failedStateTakesHighestPriority() {
        val state = pill(syncFailedPillVisible = true, syncActivityCount = 1, showReadyToast = true)
        assertEquals(StatusPillState.Failed, state)
    }

    @Test
    fun syncingTakesPriorityOverReadyAndConnecting() {
        assertEquals(StatusPillState.Syncing, pill(syncActivityCount = 1, showReadyToast = true))
        assertEquals(StatusPillState.Syncing, pill(connectionState = DeviceConnectionState.SYNCING))
    }

    @Test
    fun readyToastTakesPriorityOverConnectingAndDisconnected() {
        val state = pill(
            connectionState = DeviceConnectionState.CONNECTING,
            showReadyToast = true,
            disconnectedPillVisible = true,
        )
        assertEquals(StatusPillState.Ready, state)
    }

    @Test
    fun connectingShowsWhenConnectingAndNothingHigher() {
        assertEquals(StatusPillState.Connecting, pill(connectionState = DeviceConnectionState.CONNECTING))
    }

    @Test
    fun disconnectedShowsWhenOnlyTheDisconnectedPillIsVisible() {
        assertEquals(StatusPillState.Disconnected, pill(disconnectedPillVisible = true))
    }

    @Test
    fun hiddenWhenNoConditionsMet() {
        assertEquals(StatusPillState.Hidden, pill())
    }
}
