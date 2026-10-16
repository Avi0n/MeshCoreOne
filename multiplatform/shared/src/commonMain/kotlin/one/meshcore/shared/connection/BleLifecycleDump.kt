package one.meshcore.shared.connection

/** iOS `Resync loop already running, skipping foreground trigger`. */
const val RESYNC_SKIP_FOREGROUND_TRIGGER = "Resync loop already running, skipping foreground trigger"

/** iOS `Foreground return: sync state is failed, starting resync loop`. */
const val FOREGROUND_SYNC_FAILED_START_RESYNC =
    "Foreground return: sync state is failed, starting resync loop"

/** iOS `[BLE] ConnectionManager: re-armed watchdog on foreground while disconnected`. */
const val BLE_REARMED_WATCHDOG_FOREGROUND =
    "[BLE] ConnectionManager: re-armed watchdog on foreground while disconnected"

fun bleLifecycleEnteringBackgroundLine(transport: String, intent: String, state: String): String =
    "[BLE] Lifecycle transition: entering background, " +
        "transport: $transport, " +
        "connectionIntent: $intent, " +
        "connectionState: $state"

fun bleLifecycleBecomingActiveLine(transport: String, intent: String, state: String): String =
    "[BLE] Lifecycle transition: becoming active, " +
        "transport: $transport, " +
        "connectionIntent: $intent, " +
        "connectionState: $state"

const val BLE_LIFECYCLE_BACKGROUNDED_COMPLETE_LINE = "[BLE] Lifecycle transition complete: backgrounded"

fun bleLifecycleActiveCompleteLine(state: String): String =
    "[BLE] Lifecycle transition complete: active health check finished, connectionState: $state"

fun lifecycleTransportName(type: TransportType?): String = type.dumpName()

fun lifecycleStateName(state: ConnectionState): String = connectionStateDumpName(state)
