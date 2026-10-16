package one.meshcore.shared.connection

/** iOS `[BLE] Auto-reconnect UI timeout (Ns) fired - transitioning UI to disconnected ...`. */
fun bleReconnectUiTimeoutLine(seconds: Long): String =
    "[BLE] Auto-reconnect UI timeout (${seconds}s) fired - transitioning UI to disconnected " +
        "(reconnect continues in background)"

fun bleIgnoringConnectionLossLine(lostPrefix: String, activePrefix: String): String =
    "[BLE] Ignoring connection loss for $lostPrefix — connecting to $activePrefix"

fun connectingToDeviceLine(deviceId: String): String = "Connecting to device: $deviceId"

fun bleStateConnectedLine(handlePrefix: String): String =
    "[BLE] State → .connected (transport connected for device: $handlePrefix)"

/** iOS `[BLE] Rebuilding session for auto-reconnect:`. */
fun bleRebuildingSessionLine(handlePrefix: String): String =
    "[BLE] Rebuilding session for auto-reconnect: $handlePrefix"

fun bleRebuildSessionFailedLine(error: String): String = "[BLE] rebuildSession: session.start() failed: $error"

fun bleRebuildRetryingLine(error: String): String =
    "[BLE] Auto-reconnect session rebuild failed: $error - retrying in 2s"

fun bleRebuildPreservingLinkLine(failures: Int, budget: Int): String =
    "[BLE] Rebuild failure $failures of $budget still preserving link (sever on next)"

fun bleRebuildBudgetExhaustedLine(failures: Int): String =
    "[BLE] Rebuild preserve budget exhausted after $failures failures; severing link"

fun bleTransientConnectFailureReissueLine(failureCount: Int, budget: Int, handlePrefix: String): String =
    "[BLE] Transient auto-reconnect connect failure ($failureCount/$budget) for $handlePrefix; " +
        "re-issuing pending connect"
