package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration

/**
 * The clock read, write, and drift budget for one radio time correction.
 * getTime and setTime each get their own [commandTimeout], matching iOS
 * `syncDeviceTimeIfNeeded` so a slow read cannot spend the write's budget.
 */
internal data class ConnectionClockSync(
    val getTime: suspend () -> Long,
    val setTime: suspend (Long) -> Unit,
    val nowEpochSeconds: () -> Double,
    val commandTimeout: Duration,
    val driftToleranceSeconds: Double,
    val logInfo: (String) -> Unit,
    val logWarning: (String) -> Unit,
)

/**
 * Corrects the radio clock when it has drifted past [ConnectionClockSync.driftToleranceSeconds].
 * A command timeout is logged. Any other cancellation stops the sync. Any other failure, including
 * an [Error], is logged and the sync ends.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun syncRadioClockIfNeeded(sync: ConnectionClockSync) {
    try {
        val deviceTime = withTimeoutOrNull(sync.commandTimeout) { sync.getTime() } ?: run {
            sync.logWarning("$FAILED_SYNC_DEVICE_TIME: getTime timed out after ${sync.commandTimeout}")
            return
        }
        val now = sync.nowEpochSeconds()
        val driftSeconds = abs(deviceTime - now)
        if (driftSeconds > sync.driftToleranceSeconds) {
            withTimeoutOrNull(sync.commandTimeout) { sync.setTime(sync.nowEpochSeconds().toLong()) } ?: run {
                sync.logWarning("$FAILED_SYNC_DEVICE_TIME: setTime timed out after ${sync.commandTimeout}")
                return
            }
            sync.logInfo(syncedDeviceTimeLine(driftSeconds.toInt()))
        } else {
            sync.logInfo(deviceTimeInSyncLine(driftSeconds.toInt()))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        sync.logWarning(failedToSyncDeviceTimeLine(error))
    }
}
