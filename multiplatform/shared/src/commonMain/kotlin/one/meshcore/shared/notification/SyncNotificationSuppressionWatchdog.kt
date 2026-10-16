package one.meshcore.shared.notification

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * iOS `SyncCoordinator.startSuppressionWatchdog`. A hung connect or backlog drain must not leave
 * banners off forever: after [TIMEOUT] the flag is force-cleared if it is still set.
 */
internal object SyncNotificationSuppressionWatchdog {
    val TIMEOUT: Duration = 120.seconds

    const val FIRED_LINE: String =
        "[Sync] Notification suppression watchdog fired after 120s - force clearing"

    suspend fun awaitThenForceClear(
        stillSuppressing: () -> Boolean,
        clear: () -> Unit,
        log: (String) -> Unit,
        timeout: Duration = TIMEOUT,
    ) {
        delay(timeout)
        if (!stillSuppressing()) return
        log(FIRED_LINE)
        clear()
    }
}
