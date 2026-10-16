package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.StaleNodeRemovalResult

internal fun dangerZoneSectionTitle(embedded: Boolean, title: String): String? = if (embedded) title else null

internal fun dangerZoneResetEnabled(isDeviceConnected: Boolean, isResetting: Boolean): Boolean =
    isDeviceConnected && !isResetting

internal fun dangerZoneRemoveEnabled(
    isDeviceConnected: Boolean,
    isRemoving: Boolean,
    showRemoveSuccess: Boolean,
): Boolean = isDeviceConnected && !isRemoving && !showRemoveSuccess

internal enum class DangerZoneRemoveLabel {
    Counting,
    Removing,
    Success,
    Idle,
}

internal fun dangerZoneRemoveLabel(
    isRemoving: Boolean,
    pendingRemoveCount: Int?,
    showRemoveSuccess: Boolean,
): DangerZoneRemoveLabel = when {
    isRemoving && pendingRemoveCount == null -> DangerZoneRemoveLabel.Counting
    isRemoving -> DangerZoneRemoveLabel.Removing
    showRemoveSuccess -> DangerZoneRemoveLabel.Success
    else -> DangerZoneRemoveLabel.Idle
}

internal data class DangerZoneCountCallbacks(
    val onNone: () -> Unit,
    val onSome: (Int) -> Unit,
    val onFailure: (Throwable) -> Unit,
    val setRemoving: (Boolean) -> Unit,
)

/**
 * A disconnected or busy row ignores the tap before a coroutine starts, matching the button
 * that stays disabled for the same reasons.
 */
internal fun launchDangerZoneUnfavoritedCount(
    enabled: Boolean,
    scope: CoroutineScope,
    unfavoritedCount: suspend () -> Int,
    callbacks: DangerZoneCountCallbacks,
): Job {
    if (!enabled) {
        val skipped = Job()
        skipped.complete()
        return skipped
    }
    return scope.launch {
        runDangerZoneCommand(callbacks.onFailure, callbacks.setRemoving) {
            val count = unfavoritedCount()
            if (count == 0) callbacks.onNone() else callbacks.onSome(count)
        }
    }
}

internal suspend fun runDangerZoneForget(
    action: suspend () -> Unit,
    onLeft: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    runDangerZoneCommand(onFailure) {
        action()
        onLeft()
    }
}

internal suspend fun runDangerZoneFactoryReset(
    reset: suspend () -> Boolean,
    onLeft: () -> Unit,
    onUnavailable: () -> Unit,
    onFailure: (Throwable) -> Unit,
    setResetting: (Boolean) -> Unit,
) {
    runDangerZoneCommand(onFailure, setResetting) {
        if (reset()) onLeft() else onUnavailable()
    }
}

internal suspend fun applyDangerZoneRemoval(
    remove: suspend () -> StaleNodeRemovalResult,
    onFull: suspend () -> Unit,
    onPartial: (StaleNodeRemovalResult) -> Unit,
    onFailure: (Throwable) -> Unit,
    setRemoving: (Boolean) -> Unit,
) {
    runDangerZoneCommand(onFailure, setRemoving) {
        val result = remove()
        if (result.removed == result.total) onFull() else onPartial(result)
    }
}
