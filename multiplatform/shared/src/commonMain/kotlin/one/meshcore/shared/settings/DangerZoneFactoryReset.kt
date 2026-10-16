package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException

/**
 * Mirrors iOS `DangerZoneViewModel.factoryReset`. A missing session is a user-visible failure and
 * must not forget the device. A send timeout is expected because the radio reboots before ACKing;
 * [afterExpectedReboot] still runs. Cancellation does not forget the device.
 */
suspend fun runDangerZoneFactoryReset(
    servicesAvailable: Boolean,
    sendReset: suspend () -> Unit,
    afterExpectedReboot: suspend () -> Unit,
): Boolean {
    if (!servicesAvailable) return false
    try {
        sendReset()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The radio reboots before the ACK. Forgetting the device still runs.
    }
    afterExpectedReboot()
    return true
}
