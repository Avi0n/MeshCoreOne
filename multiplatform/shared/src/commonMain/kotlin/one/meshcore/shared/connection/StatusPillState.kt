package one.meshcore.shared.connection

/**
 * The rendered state of the top-of-screen connection status pill, ported from the iOS `StatusPillState`.
 * Icons, colors, and localized text live in the UI layer. [Failed] is the sync-failed pill; its copy is
 * the same status string the other states resolve in the pill view.
 */
sealed interface StatusPillState {
    data object Hidden : StatusPillState
    data object Connecting : StatusPillState
    data object Syncing : StatusPillState
    data object Ready : StatusPillState
    data object Disconnected : StatusPillState
    data object Failed : StatusPillState
}

/**
 * The inputs the status pill is computed from: the independently-timed connection-UI flags plus the
 * connection rung.
 */
data class StatusPillInputs(
    val syncFailedPillVisible: Boolean,
    val syncActivityCount: Int,
    val connectionState: DeviceConnectionState,
    val showReadyToast: Boolean,
    val disconnectedPillVisible: Boolean,
)

/**
 * Computes the pill state, preserving the iOS priority ladder exactly:
 *
 * `failed > syncing > ready > connecting > disconnected > hidden`
 */
fun statusPillState(inputs: StatusPillInputs): StatusPillState {
    if (inputs.syncFailedPillVisible) return StatusPillState.Failed
    if (inputs.syncActivityCount > 0 || inputs.connectionState == DeviceConnectionState.SYNCING) {
        return StatusPillState.Syncing
    }
    if (inputs.showReadyToast) return StatusPillState.Ready
    if (inputs.connectionState == DeviceConnectionState.CONNECTING) return StatusPillState.Connecting
    if (inputs.disconnectedPillVisible) return StatusPillState.Disconnected
    return StatusPillState.Hidden
}
