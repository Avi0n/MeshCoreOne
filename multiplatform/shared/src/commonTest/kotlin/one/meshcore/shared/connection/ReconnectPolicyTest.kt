package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Truth tables for the ported discovery watchdog and backoff schedule, mirroring the Swift
 * `BLEStateMachineTimeoutPolicyTests` / `BLEStateMachineDiscoveryTimeoutClassificationTests`.
 */
class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()
    private val max = policy.maxDiscoveryTimeoutExtensions

    @Test
    fun connectedLinkWithBudgetExtendsTheDiscoveryWindow() {
        assertTrue(policy.shouldExtendDiscoveryTimeout(LinkPeripheralState.CONNECTED, extensions = 0))
    }

    @Test
    fun connectedLinkWithSpentBudgetDoesNotExtend() {
        assertFalse(policy.shouldExtendDiscoveryTimeout(LinkPeripheralState.CONNECTED, extensions = max))
    }

    @Test
    fun aLinkThatNeverConnectedNeverExtends() {
        for (state in listOf(
            LinkPeripheralState.DISCONNECTED,
            LinkPeripheralState.CONNECTING,
            LinkPeripheralState.DISCONNECTING,
        )) {
            assertFalse(policy.shouldExtendDiscoveryTimeout(state, extensions = 0))
        }
    }

    @Test
    fun connectedLinkWithSpentBudgetIsClassifiedAsBondLoss() {
        assertEquals(
            LinkTeardownClassification.BOND_LOST,
            policy.discoveryTimeoutClassification(LinkPeripheralState.CONNECTED, extensions = max),
        )
    }

    @Test
    fun connectedLinkWithinBudgetIsAPlainConnectionTimeout() {
        assertEquals(
            LinkTeardownClassification.CONNECTION_TIMEOUT,
            policy.discoveryTimeoutClassification(LinkPeripheralState.CONNECTED, extensions = 0),
        )
    }

    @Test
    fun aLinkThatNeverReachedConnectedIsAPlainConnectionTimeout() {
        for (state in listOf(
            LinkPeripheralState.DISCONNECTED,
            LinkPeripheralState.CONNECTING,
            LinkPeripheralState.DISCONNECTING,
        )) {
            assertEquals(
                LinkTeardownClassification.CONNECTION_TIMEOUT,
                policy.discoveryTimeoutClassification(state, extensions = max),
                "state $state should be a connection timeout, not a bond loss",
            )
        }
    }

    @Test
    fun watchdogBackoffDoublesToTheCap() {
        assertEquals(30.seconds, policy.watchdogBackoff(iteration = 0))
        assertEquals(60.seconds, policy.watchdogBackoff(iteration = 1))
        assertEquals(120.seconds, policy.watchdogBackoff(iteration = 2))
        assertEquals(120.seconds, policy.watchdogBackoff(iteration = 5))
    }

    @Test
    fun sessionRebuildPreserveBudgetMatchesIos() {
        assertEquals(2.seconds, policy.sessionRebuildRetryDelay)
        assertEquals(3, policy.maxRebuildFailuresPreservingLink)
    }

    // MARK: - Connect-failure classification (transient retry, bond-suspect escalation, grace)

    private val budget = policy.maxAutoReconnectConnectFailures

    private data class ClassifyOptions(
        val encryptionTimeoutFailures: Int = 0,
        val lastVerified: Double? = null,
        val now: Double = NOW,
        val appActive: Boolean = true,
    )

    private fun classify(
        kind: ConnectFailureKind,
        connectFailures: Int,
        options: ClassifyOptions = ClassifyOptions(),
    ): ConnectFailureDecision = policy.classifyConnectFailure(
        ConnectFailureFacts(
            kind = kind,
            connectFailures = connectFailures,
            encryptionTimeoutFailures = options.encryptionTimeoutFailures,
            lastBondVerificationEpochSeconds = options.lastVerified,
            nowEpochSeconds = options.now,
            appActive = options.appActive,
        ),
    )

    @Test
    fun aDefinitiveBondFailureEscalatesImmediatelyRegardlessOfCounts() {
        assertEquals(
            ConnectFailureDecision.ESCALATE_BOND_LOST,
            classify(ConnectFailureKind.DEFINITIVE_BOND, connectFailures = 1),
        )
    }

    @Test
    fun aTransientFailureUnderBudgetRetries() {
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(ConnectFailureKind.TRANSIENT, connectFailures = budget - 1),
        )
    }

    @Test
    fun aSpentBudgetOfOrdinaryTransientFailuresKeepsRetrying() {
        // Out-of-range failures never escalate: the Android watchdog must keep trying, not give up.
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(
                ConnectFailureKind.TRANSIENT,
                connectFailures = budget,
                options = ClassifyOptions(encryptionTimeoutFailures = 1),
            ),
        )
    }

    @Test
    fun anEncryptionTimeoutMajorityWithNoVerifiedSessionEscalates() {
        assertEquals(
            ConnectFailureDecision.ESCALATE_BOND_LOST,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = budget,
                    lastVerified = null,
                ),
            ),
        )
    }

    @Test
    fun anEncryptionTimeoutMajorityIsGracedByARecentlyVerifiedSession() {
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = budget,
                    lastVerified = NOW - 1.0,
                ),
            ),
        )
    }

    @Test
    fun anEncryptionTimeoutMajorityEscalatesOnceTheGraceElapses() {
        val stale = NOW - (policy.bondVerificationGrace.inWholeSeconds + 1)
        assertEquals(
            ConnectFailureDecision.ESCALATE_BOND_LOST,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = budget,
                    lastVerified = stale,
                ),
            ),
        )
    }

    @Test
    fun anUnshieldedEncryptionTimeoutMajorityHoldsWhileInactive() {
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = budget,
                    lastVerified = null,
                    appActive = false,
                ),
            ),
        )
        val stale = NOW - (policy.bondVerificationGrace.inWholeSeconds + 1)
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = budget,
                    lastVerified = stale,
                    appActive = false,
                ),
            ),
        )
    }

    @Test
    fun aDefinitiveBondFailureEscalatesEvenWhileInactive() {
        assertEquals(
            ConnectFailureDecision.ESCALATE_BOND_LOST,
            classify(
                ConnectFailureKind.DEFINITIVE_BOND,
                connectFailures = 1,
                options = ClassifyOptions(appActive = false),
            ),
        )
    }

    @Test
    fun aMinorityOfEncryptionTimeoutsInASpentBudgetKeepsRetrying() {
        assertEquals(
            ConnectFailureDecision.RETRY,
            classify(
                ConnectFailureKind.ENCRYPTION_TIMEOUT,
                connectFailures = budget,
                options = ClassifyOptions(
                    encryptionTimeoutFailures = 2,
                    lastVerified = null,
                ),
            ),
        )
    }

    @Test
    fun bondVerificationRecencyMatchesTheGraceWindow() {
        assertFalse(policy.isBondRecentlyVerified(lastBondVerificationEpochSeconds = null, nowEpochSeconds = NOW))
        assertTrue(policy.isBondRecentlyVerified(lastBondVerificationEpochSeconds = NOW - 1.0, nowEpochSeconds = NOW))
        // A future stamp (clock set backward) counts as recent — the non-destructive direction.
        assertTrue(policy.isBondRecentlyVerified(lastBondVerificationEpochSeconds = NOW + 1.0, nowEpochSeconds = NOW))
        val stale = NOW - (policy.bondVerificationGrace.inWholeSeconds + 1)
        assertFalse(policy.isBondRecentlyVerified(lastBondVerificationEpochSeconds = stale, nowEpochSeconds = NOW))
    }

    @Test
    fun refreshBondVerificationUpdatesAnExistingStampAndNeverCreates() {
        val stamps = mutableMapOf<String, Double>()
        assertFalse(policy.refreshBondVerification(stamps, DEVICE, NOW))
        assertTrue(stamps.isEmpty())

        stamps[DEVICE] = NOW - 3_600.0
        assertTrue(policy.refreshBondVerification(stamps, DEVICE, NOW))
        assertEquals(NOW, stamps[DEVICE])

        assertFalse(policy.refreshBondVerification(stamps, OTHER, NOW))
        assertNull(stamps[OTHER])

        stamps.remove(DEVICE)
        assertFalse(policy.refreshBondVerification(stamps, DEVICE, NOW))
        assertNull(stamps[DEVICE])
    }

    private companion object {
        const val NOW = 1_000_000.0
        const val DEVICE = "device-1"
        const val OTHER = "device-other"
    }
}
