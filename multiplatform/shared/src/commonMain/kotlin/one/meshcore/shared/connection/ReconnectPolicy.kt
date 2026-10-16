package one.meshcore.shared.connection

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The link-layer peripheral state a reconnect watchdog reasons about, mirroring CoreBluetooth's
 * `CBPeripheralState`. Kable does not expose this granularity directly, so the classification below is
 * fed whatever state a link implementation can observe (Kable's `State`, or a fake in tests).
 */
enum class LinkPeripheralState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

/**
 * How a reconnect/discovery teardown is classified. A peripheral that reached link-layer
 * [LinkPeripheralState.CONNECTED] yet never completed discovery across the full extension budget is
 * the strongest in-app signal of a silently invalidated bond, so it escalates to [BOND_LOST];
 * everything else is a plain [CONNECTION_TIMEOUT]. Mirrors Swift's `BLEError.authenticationFailed`
 * vs `.connectionTimeout` split.
 */
enum class LinkTeardownClassification {
    CONNECTION_TIMEOUT,
    BOND_LOST,
}

/**
 * How a link *connect* failure during an auto-reconnect episode is classified. The Android analog of the
 * Swift `CBError` split: an [ENCRYPTION_TIMEOUT] (GATT status 133/8/22-class transient auth failure) is a
 * weak link on its own but the ambiguous signature of an invalidated bond when it dominates a spent retry
 * budget; a [DEFINITIVE_BOND] failure (peer removed pairing, insufficient encryption) is never retried;
 * everything else is [TRANSIENT] (out of range, GATT busy) and simply re-arms the backoff.
 */
enum class ConnectFailureKind {
    TRANSIENT,
    ENCRYPTION_TIMEOUT,
    DEFINITIVE_BOND,
}

/**
 * What the reconnect loop does after a connect attempt fails, mirroring the actionable arms of Swift's
 * `ReconnectPolicy.resolveConnectFailure`. [Retry] re-arms the backoff and stays in the episode (the
 * Android foreground-service watchdog keeps trying); [EscalateBondLost] surfaces
 * [ConnectionState.BondLost] for guided re-pair, reached only by a definitive bond failure or an
 * encryption-timeout-dominated budget with no recent verified session.
 */
enum class ConnectFailureDecision {
    RETRY,
    ESCALATE_BOND_LOST,
}

data class ConnectFailureFacts(
    val kind: ConnectFailureKind,
    val connectFailures: Int,
    val encryptionTimeoutFailures: Int,
    val lastBondVerificationEpochSeconds: Double?,
    val nowEpochSeconds: Double,
    val appActive: Boolean,
)

/**
 * The pure reconnect/backoff/classification logic ported from `BLEStateMachine` (the discovery
 * watchdog truth tables) and `ConnectionManager` (the connect-retry and watchdog backoff schedules).
 *
 * These are deliberately side-effect-free so the transition behaviour can be unit-tested without a
 * radio, exactly as the Swift `BLEStateMachineTimeoutPolicyTests` exercise the static functions.
 */
class ReconnectPolicy(config: Config = Config()) {
    /** Bound on connected-but-wedged discovery extensions before a forced teardown (Swift's cap). */
    val maxDiscoveryTimeoutExtensions: Int = config.maxDiscoveryTimeoutExtensions

    /**
     * Max consecutive connect failures tolerated in one auto-reconnect episode before the encryption
     * majority is re-checked. Bounds the escalation window so a bond that fast-rejects every connect
     * reaches a decision instead of spinning. Swift's `maxAutoReconnectConnectFailures`.
     */
    val maxAutoReconnectConnectFailures: Int = config.maxAutoReconnectConnectFailures

    /**
     * How long after a verified encrypted session an exhausted encryption-timeout budget is still treated
     * as a weak link rather than a suspect bond. Encryption timeouts at the edge of BLE range are
     * indistinguishable from an invalidated bond attempt-by-attempt; a bond that completed a session this
     * recently is near-certainly healthy, while a dead bond can never refresh the stamp and escalates once
     * the grace elapses. Swift's `bondVerificationGraceInterval` (6h).
     */
    val bondVerificationGrace: Duration = config.bondVerificationGrace

    /** First reconnect-watchdog backoff; doubles each iteration up to [maxBackoff]. Swift's 30s base. */
    private val baseBackoff: Duration = config.baseBackoff

    /** Ceiling for the reconnect-watchdog backoff. Swift's 120s cap. */
    private val maxBackoff: Duration = config.maxBackoff

    /**
     * How often a live BLE session refreshes an existing bond stamp. Matches Swift's 15s RSSI
     * keepalive. Injectable so tests can fire the refresh without waiting on production cadence.
     */
    val bondRefreshInterval: Duration = config.bondRefreshInterval

    /**
     * How long the reconnect pill may stay on Connecting. iOS `maxConnectingUIWindow` (60s): the 15s
     * UI timer re-arms while CoreBluetooth is still auto-reconnecting, then stands the UI down to
     * `.disconnected` so backup import and the pill are not blocked for the whole episode. Android's
     * loop is that episode, so this is the one cap.
     */
    val maxConnectingUiWindow: Duration = config.maxConnectingUiWindow

    /**
     * Pause after a failed in-place session rebuild before retrying handshake on the live GATT.
     * iOS `BLEReconnectionCoordinator.retryRebuild` sleeps 2s.
     */
    val sessionRebuildRetryDelay: Duration = config.sessionRebuildRetryDelay

    /**
     * How many consecutive live-GATT handshake failures keep the link up. The next failure severs
     * it so a fresh `autoConnect` can run. iOS `maxRebuildFailuresPreservingLink`.
     */
    val maxRebuildFailuresPreservingLink: Int = config.maxRebuildFailuresPreservingLink

    /**
     * Whether a discovery-phase watchdog should extend its window instead of tearing the link down.
     * When the peripheral is already [LinkPeripheralState.CONNECTED] the link is up and a discovery
     * callback is merely slow; tearing it down would kill a working reconnection. Extend a bounded
     * number of times so a genuinely wedged link still recovers.
     */
    fun shouldExtendDiscoveryTimeout(peripheralState: LinkPeripheralState, extensions: Int): Boolean =
        peripheralState == LinkPeripheralState.CONNECTED && extensions < maxDiscoveryTimeoutExtensions

    /**
     * Classifies a discovery/subscribe watchdog teardown. Connected-but-spent-budget is a suspected
     * bond loss; every other state is a plain connection timeout.
     */
    fun discoveryTimeoutClassification(
        peripheralState: LinkPeripheralState,
        extensions: Int,
    ): LinkTeardownClassification =
        if (peripheralState == LinkPeripheralState.CONNECTED && extensions >= maxDiscoveryTimeoutExtensions) {
            LinkTeardownClassification.BOND_LOST
        } else {
            LinkTeardownClassification.CONNECTION_TIMEOUT
        }

    /**
     * The reconnection-watchdog backoff for cycle `iteration` (0-based): the base doubled `iteration`
     * times, capped at [maxBackoff] — `30s -> 60s -> 120s` with the defaults, mirroring
     * `ConnectionManager.startReconnectionWatchdog`.
     */
    fun watchdogBackoff(iteration: Int): Duration {
        require(iteration >= 0) { "iteration is 0-based" }
        val doublings = min(iteration, MAX_BACKOFF_DOUBLINGS)
        return minOf(baseBackoff * (1 shl doublings), maxBackoff)
    }

    /**
     * Classifies a connect failure during an auto-reconnect episode. `connectFailures` and
     * `encryptionTimeoutFailures` are the running consecutive tallies for the episode *including* this
     * failure; `lastBondVerificationEpochSeconds` is when this device last completed a verified encrypted
     * session (null when it never did), `nowEpochSeconds` is the decision clock, and `appActive` is
     * whether the UI is confirmed in the foreground. The policy never reads the clock or scene phase
     * itself, so every decision is reproducible from its inputs.
     *
     * A definitive bond failure escalates at once. Under budget, or on a budget spent by ordinary
     * transient failures, the loop retries — the watchdog keeps trying rather than giving up (the OS
     * cannot re-arm a background reconnect on Android, so a live loop is the only recovery). Only an
     * encryption-timeout-dominated budget escalates, and only when the app is confirmed active and no
     * session verified within the grace. [appActive] starts false (a process relaunch is already
     * background) so an unshielded majority holds until the user is on screen, matching iOS
     * `ReconnectPolicy.resolveConnectFailure` `backgroundHold`.
     */
    fun classifyConnectFailure(facts: ConnectFailureFacts): ConnectFailureDecision = when {
        facts.kind == ConnectFailureKind.DEFINITIVE_BOND -> ConnectFailureDecision.ESCALATE_BOND_LOST
        facts.connectFailures < maxAutoReconnectConnectFailures -> ConnectFailureDecision.RETRY
        // Ordinary transient failures never escalate: the watchdog keeps trying rather than giving up.
        facts.encryptionTimeoutFailures * 2 <= facts.connectFailures -> ConnectFailureDecision.RETRY
        // An encryption-timeout majority is graced while a verified session is recent; only a bond that
        // cannot refresh the stamp tips to suspect once the grace elapses.
        isBondRecentlyVerified(
            facts.lastBondVerificationEpochSeconds,
            facts.nowEpochSeconds,
        ) -> ConnectFailureDecision.RETRY
        // iOS `backgroundHold`: do not surface Bond Lost while inactive. A fringe encryption-timeout
        // majority while the screen is off is out-of-range noise, not a user-visible re-pair.
        !facts.appActive -> ConnectFailureDecision.RETRY
        else -> ConnectFailureDecision.ESCALATE_BOND_LOST
    }

    /**
     * Whether a bond verification is recent enough to shield an exhausted encryption-timeout budget from
     * bond-suspect escalation. A missing stamp (never verified) gives no shield; a future stamp (clock set
     * backward) counts as recent — the non-destructive direction, matching the Swift policy.
     */
    fun isBondRecentlyVerified(lastBondVerificationEpochSeconds: Double?, nowEpochSeconds: Double): Boolean {
        val last = lastBondVerificationEpochSeconds ?: return false
        return nowEpochSeconds - last < bondVerificationGrace.inWholeMilliseconds / MILLIS_PER_SECOND
    }

    /**
     * Refreshes an existing verification's timestamp; never creates one. Only a completed handshake
     * is evidence that a bond verified, so a forgotten pairing has no entry and a keepalive tick
     * cannot re-shield it. Returns true when an existing stamp was updated.
     */
    fun refreshBondVerification(
        stamps: MutableMap<String, Double>,
        deviceId: String,
        nowEpochSeconds: Double,
    ): Boolean {
        if (stamps[deviceId] == null) return false
        stamps[deviceId] = nowEpochSeconds
        return true
    }

    data class Config(
        val maxDiscoveryTimeoutExtensions: Int = DEFAULT_MAX_DISCOVERY_EXTENSIONS,
        val maxAutoReconnectConnectFailures: Int = DEFAULT_MAX_AUTO_RECONNECT_CONNECT_FAILURES,
        val bondVerificationGrace: Duration = DEFAULT_BOND_VERIFICATION_GRACE,
        val baseBackoff: Duration = DEFAULT_BASE_BACKOFF,
        val maxBackoff: Duration = DEFAULT_MAX_BACKOFF,
        val bondRefreshInterval: Duration = DEFAULT_BOND_REFRESH_INTERVAL,
        val maxConnectingUiWindow: Duration = DEFAULT_MAX_CONNECTING_UI_WINDOW,
        val sessionRebuildRetryDelay: Duration = DEFAULT_SESSION_REBUILD_RETRY,
        val maxRebuildFailuresPreservingLink: Int = DEFAULT_MAX_REBUILD_FAILURES_PRESERVING_LINK,
    ) {
        companion object {
            const val DEFAULT_MAX_DISCOVERY_EXTENSIONS = 2
            const val DEFAULT_MAX_AUTO_RECONNECT_CONNECT_FAILURES = 5
            const val DEFAULT_MAX_REBUILD_FAILURES_PRESERVING_LINK = 3

            private val DEFAULT_BOND_VERIFICATION_GRACE = (6 * 60 * 60).seconds
            private val DEFAULT_BASE_BACKOFF = 30.seconds
            private val DEFAULT_MAX_BACKOFF = 120.seconds
            private val DEFAULT_BOND_REFRESH_INTERVAL = 15.seconds
            private val DEFAULT_MAX_CONNECTING_UI_WINDOW = 60.seconds
            private val DEFAULT_SESSION_REBUILD_RETRY = 2.seconds
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        // Bounds the shift so a long-lived reconnect loop cannot overflow the doubling; the cap clamps
        // the value long before this anyway.
        private const val MAX_BACKOFF_DOUBLINGS = 16
    }
}
