package one.meshcore.shared.remotenode

/** iOS `RemoteNodeService.handleCLIResponse` preview length. */
const val CLI_RESPONSE_LOG_PREFIX = 50

/** iOS `RemoteNodeService.handleCLIResponse` unmatched-debug dump. */
fun unmatchedCliResponseLine(preview: String): String = "Unmatched CLI response (no pending request): $preview"

/** iOS `RemoteNodeService.handleCLIResponse` stale-prefix dump. */
fun droppingStaleCliResponseLine(echoedPrefix: String, awaitingPrefix: String): String =
    "Dropping stale CLI response with prefix $echoedPrefix while awaiting $awaitingPrefix"

/** iOS `RemoteNodeService.handleCLIResponse` shape-mismatch dump. */
fun droppingCliResponseMismatchLine(command: String, preview: String): String =
    "Dropping CLI response that doesn't match pending '$command': $preview"

/** iOS `SyncCoordinator` CLI fan-out when the sender has no local contact. */
const val DROPPING_CLI_RESPONSE_NO_CONTACT = "Dropping CLI response: no contact found for sender"
