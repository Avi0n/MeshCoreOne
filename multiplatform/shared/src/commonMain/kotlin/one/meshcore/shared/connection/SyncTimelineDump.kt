package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid

/** iOS `[Sync] State → .synced`. */
const val SYNC_STATE_SYNCED = "[Sync] State → .synced"

/** iOS `[Sync] State → .idle (disconnected)`. */
const val SYNC_STATE_IDLE_DISCONNECTED = "[Sync] State → .idle (disconnected)"

/** iOS `Disconnected, sync state reset to idle`. */
const val SYNC_DISCONNECTED_RESET = "Disconnected, sync state reset to idle"

/** iOS `isSyncInProgress still true at disconnect — clearing as safety net`. */
const val SYNC_IN_PROGRESS_AT_DISCONNECT =
    "isSyncInProgress still true at disconnect — clearing as safety net"

/** iOS `[Sync] Skipping channel sync (recent clean or attempted sync)`. */
const val SYNC_SKIP_CHANNEL = "[Sync] Skipping channel sync (recent clean or attempted sync)"

/** iOS `Skipping channel sync (app in background)`. */
const val SYNC_SKIP_CHANNEL_BACKGROUND = "[Sync] Skipping channel sync (app in background)"

/** iOS `Timed out waiting for advert contact sync to release claim`. */
const val ADVERT_CLAIM_WAIT_TIMED_OUT =
    "Timed out waiting for advert contact sync to release claim"

/** iOS `Channel-only retry timed out waiting for advert contact sync`. */
const val CHANNEL_ONLY_RETRY_ADVERT_WAIT_TIMED_OUT =
    "Channel-only retry timed out waiting for advert contact sync"

/** iOS `[Sync] Calling onSyncActivityStarted`. */
const val SYNC_CALLING_ACTIVITY_STARTED = "[Sync] Calling onSyncActivityStarted"

/** iOS `[Sync] Calling onSyncActivityEnded (succeeded: \(succeeded))`. */
fun syncCallingActivityEndedLine(succeeded: Boolean): String =
    "[Sync] Calling onSyncActivityEnded (succeeded: $succeeded)"

private const val RADIO_ID_PREFIX_LENGTH: Int = 8

fun syncStateSyncingLine(phase: String): String = "[Sync] State → .syncing(.$phase)"

fun connectionEstablishedLine(radioId: Uuid): String = "Connection established for device ${radioId.value}"

fun startingFullSyncLine(radioId: Uuid): String = "Starting full sync for device ${radioId.value}"

fun syncContactsPhaseStartLine(incremental: Boolean, detail: String): String = if (incremental) {
    "[Sync] Phase start: contacts (incremental, watermark=$detail)"
} else {
    "[Sync] Phase start: contacts (FULL sync, reason=$detail) — local contacts not on device will be pruned"
}

fun syncCompleteLine(platform: String, messageCount: Int, durationSeconds: Double): String =
    "[Sync] Complete: platform=$platform, messages=$messageCount, duration=${durationSeconds}s"

fun syncStartingEventMonitoringLine(radioId: Uuid): String =
    "[Sync] Starting event monitoring for device ${radioIdPrefix(radioId)}"

fun syncStartingAutoFetchLine(radioId: Uuid): String = "[Sync] Starting auto-fetch for device ${radioIdPrefix(radioId)}"

fun connectionSetupCompleteLine(radioId: Uuid): String = "Connection setup complete for device ${radioId.value}"

private fun radioIdPrefix(radioId: Uuid): String = radioId.value.take(RADIO_ID_PREFIX_LENGTH)
