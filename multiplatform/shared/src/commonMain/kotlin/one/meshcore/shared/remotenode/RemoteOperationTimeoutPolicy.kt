package one.meshcore.shared.remotenode

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.MessageSentInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Login timeout as a function of path length, mirroring the Swift `LoginTimeoutConfig`. A direct (0-hop)
 * path gets [DIRECT_TIMEOUT]; each hop adds [PER_HOP_TIMEOUT], capped at [MAXIMUM_TIMEOUT].
 */
object LoginTimeoutConfig {
    val DIRECT_TIMEOUT: Duration = 5.seconds
    val PER_HOP_TIMEOUT: Duration = 10.seconds
    val MAXIMUM_TIMEOUT: Duration = 60.seconds

    fun timeout(pathLength: Int): Duration {
        // An undecodable byte (the reserved hash mode, notably the 0xFF flood sentinel) means no known
        // path: the login floods both ways, so budget the worst case rather than a zero-hop exchange.
        val decoded = PathEncoding.decodePathLen(pathLength) ?: return MAXIMUM_TIMEOUT
        val total = DIRECT_TIMEOUT + PER_HOP_TIMEOUT * decoded.hopCount
        return minOf(total, MAXIMUM_TIMEOUT)
    }
}

/**
 * Timeout ceilings and derivations for remote-node operations, mirroring the Swift
 * `RemoteOperationTimeoutPolicy`. The firmware's suggested round-trip is doubled, then clamped by the
 * per-operation ceiling.
 */
object RemoteOperationTimeoutPolicy {
    const val FIRMWARE_ROUND_TRIP_MULTIPLIER: Int = 2
    val LOGIN_MAXIMUM: Duration = 20.seconds

    // Floor for login retransmit spacing while waiting for the `loginSuccess` push; raised to the firmware's
    // suggested round-trip when larger so multi-hop paths are not flooded mid-flight. Mirrors Swift.
    val LOGIN_RETRANSMIT_INTERVAL: Duration = 1.seconds

    // Sized for the in-session retransmit budget MeshCore runs for multi-hop status/telemetry/neighbours;
    // the outer ceiling must not cut off a bridged retransmit sequence. Mirrors Swift `binaryMaximum`.
    val BINARY_MAXIMUM: Duration = 45.seconds
    val CLI_MAXIMUM: Duration = 15.seconds

    /**
     * Default wait for a CLI reply. Generous because LoRa replies routinely take several seconds and
     * waiting costs no airtime, unlike a resend. Mirrors Swift `defaultCLITimeout`.
     */
    val DEFAULT_CLI: Duration = 10.seconds

    /**
     * Wait for commands that get no reply by design (`reboot`); long enough to catch an error reply,
     * short enough not to read as a failure to the user.
     */
    val FIRE_AND_FORGET_CLI: Duration = 2.seconds
    val POLL_INTERVAL: Duration = 500.milliseconds

    fun firmwareRoundTripTimeout(sentInfo: MessageSentInfo): Duration =
        (sentInfo.suggestedTimeoutMs * FIRMWARE_ROUND_TRIP_MULTIPLIER).milliseconds

    /** `min(max(firmwareRoundTrip, LoginTimeoutConfig(pathLength)), 20s)`. */
    fun loginTimeout(sentInfo: MessageSentInfo, pathLength: Int): Duration =
        minOf(maxOf(firmwareRoundTripTimeout(sentInfo), LoginTimeoutConfig.timeout(pathLength)), LOGIN_MAXIMUM)

    /** `min(max(requestedTimeout, firmwareRoundTrip), 15s)`. */
    fun cliTimeout(sentInfo: MessageSentInfo, requestedTimeout: Duration): Duration =
        minOf(maxOf(requestedTimeout, firmwareRoundTripTimeout(sentInfo)), CLI_MAXIMUM)
}
