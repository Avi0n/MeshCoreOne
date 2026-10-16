package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid

/** iOS `MessagePollingService.stopAutoFetch`. */
const val AUTO_FETCH_STOPPED = "Auto-fetch stopped"

/** iOS `Message event monitoring started for radio \(radioID)`. */
fun messageEventMonitoringStartedLine(radioId: Uuid): String =
    "Message event monitoring started for radio ${radioId.value}"

/** iOS `Auto-fetch started for radio \(radioID)`. */
fun autoFetchStartedLine(radioId: Uuid): String = "Auto-fetch started for radio ${radioId.value}"

/** iOS `[Sync] Pausing auto-fetch for resync`. */
const val PAUSING_AUTO_FETCH_FOR_RESYNC = "[Sync] Pausing auto-fetch for resync"

/** iOS `[Sync] Resuming auto-fetch after resync`. */
const val RESUMING_AUTO_FETCH_AFTER_RESYNC = "[Sync] Resuming auto-fetch after resync"
