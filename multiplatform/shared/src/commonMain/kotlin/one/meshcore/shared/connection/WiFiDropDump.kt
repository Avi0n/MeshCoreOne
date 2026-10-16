package one.meshcore.shared.connection

/** iOS `WiFi heartbeat skipped while sync activity is active`. */
const val WIFI_HEARTBEAT_SKIPPED = "WiFi heartbeat skipped while sync activity is active"

/** iOS `WiFi heartbeat timeout during sync activity treated as inconclusive`. */
const val WIFI_HEARTBEAT_INCONCLUSIVE = "WiFi heartbeat timeout during sync activity treated as inconclusive"

/** iOS `WiFi heartbeat failed:` prefix. */
const val WIFI_HEARTBEAT_FAILED_PREFIX = "WiFi heartbeat failed"

/** iOS `WiFi connection lost:` prefix. */
const val WIFI_CONNECTION_LOST_PREFIX = "WiFi connection lost"

/** iOS `[WiFi] State → .connecting (WiFi disconnection, starting reconnection)`. */
const val WIFI_STATE_CONNECTING_ON_DROP =
    "[WiFi] State → .connecting (WiFi disconnection, starting reconnection)"

/** iOS `Suppressing WiFi reconnection: too soon after last attempt`. */
const val WIFI_RECONNECT_SUPPRESSED = "Suppressing WiFi reconnection: too soon after last attempt"

/** iOS `WiFi reconnection timeout after 30s`. */
const val WIFI_RECONNECT_TIMEOUT = "WiFi reconnection timeout after 30s"

/** iOS `WiFi reconnection succeeded`. */
const val WIFI_RECONNECT_SUCCEEDED = "WiFi reconnection succeeded"

/** iOS `WiFi reconnect failed:` prefix. */
const val WIFI_RECONNECT_FAILED_PREFIX = "WiFi reconnect failed"

/** iOS `Connecting via WiFi to`. */
const val WIFI_CONNECTING_PREFIX = "Connecting via WiFi to"

/** iOS `WiFi connection complete - device ready`. */
const val WIFI_CONNECTION_COMPLETE = "WiFi connection complete - device ready"

fun wifiHeartbeatFailedLine(error: String): String = "$WIFI_HEARTBEAT_FAILED_PREFIX: $error"

fun wifiConnectionLostLine(error: String): String = "$WIFI_CONNECTION_LOST_PREFIX: $error"

fun wifiReconnectAttemptLine(attempt: Int): String = "WiFi reconnect attempt $attempt"

fun wifiReconnectFailedLine(error: String): String = "$WIFI_RECONNECT_FAILED_PREFIX: $error"

fun wifiConnectingLine(host: String, port: Int): String = "$WIFI_CONNECTING_PREFIX $host:$port"

/** iOS `WiFi connection died while backgrounded`. */
const val WIFI_FOREGROUND_TRANSPORT_DEAD = "WiFi connection died while backgrounded"

/** iOS `WiFi reconnection already in progress on foreground`. */
const val WIFI_FOREGROUND_RECONNECT_IN_PROGRESS = "WiFi reconnection already in progress on foreground"

/** iOS `WiFi foreground reconnect to` prefix. */
const val WIFI_FOREGROUND_RECONNECT_PREFIX = "WiFi foreground reconnect to"

/** iOS `WiFi foreground reconnect failed:` prefix. */
const val WIFI_FOREGROUND_RECONNECT_FAILED_PREFIX = "WiFi foreground reconnect failed"

fun wifiForegroundReconnectLine(host: String, port: Int): String = "$WIFI_FOREGROUND_RECONNECT_PREFIX $host:$port"

fun wifiForegroundReconnectFailedLine(error: String): String = "$WIFI_FOREGROUND_RECONNECT_FAILED_PREFIX: $error"
