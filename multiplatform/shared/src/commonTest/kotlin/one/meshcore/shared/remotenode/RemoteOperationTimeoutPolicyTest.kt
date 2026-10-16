package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Timeout derivations, mirroring the iOS `RemoteOperationTimeoutPolicy` / `LoginTimeoutConfig` behaviour. */
class RemoteOperationTimeoutPolicyTest {
    @Test
    fun firmwareRoundTripDoublesSuggestedTimeout() {
        assertEquals(2000.milliseconds, RemoteOperationTimeoutPolicy.firmwareRoundTripTimeout(testSentInfo(1000)))
    }

    @Test
    fun loginTimeoutClampsBetweenPerHopFloorAndCeiling() {
        // Direct path: max(round-trip 2s, direct floor 5s) = 5s.
        assertEquals(5.seconds, RemoteOperationTimeoutPolicy.loginTimeout(testSentInfo(1000), pathLength = 0))
        // A huge round-trip is clamped to the 20s login ceiling.
        assertEquals(20.seconds, RemoteOperationTimeoutPolicy.loginTimeout(testSentInfo(20_000), pathLength = 0))
    }

    @Test
    fun loginTimeoutConfigAddsPerHop() {
        assertEquals(5.seconds, LoginTimeoutConfig.timeout(pathLength = 0))
        // Two hops (mode 0, hop count 2): 5s + 2 * 10s = 25s.
        assertEquals(25.seconds, LoginTimeoutConfig.timeout(pathLength = 2))
        // Ten hops would be 105s, clamped to the 60s maximum.
        assertEquals(60.seconds, LoginTimeoutConfig.timeout(pathLength = 10))
    }

    @Test
    fun floodSentinelBudgetsTheMaximum() {
        // 0xFF carries the reserved hash mode: no known path, so the worst case rather than a direct hop.
        assertEquals(60.seconds, LoginTimeoutConfig.timeout(pathLength = 0xFF))
        assertEquals(20.seconds, RemoteOperationTimeoutPolicy.loginTimeout(testSentInfo(1000), pathLength = 0xFF))
    }

    @Test
    fun cliTimeoutClampsToCeiling() {
        // max(requested 3s, round-trip 2s) = 3s.
        assertEquals(
            3.seconds,
            RemoteOperationTimeoutPolicy.cliTimeout(testSentInfo(1000), requestedTimeout = 3.seconds),
        )
        // A requested value above the ceiling clamps to 15s.
        assertEquals(
            15.seconds,
            RemoteOperationTimeoutPolicy.cliTimeout(testSentInfo(1000), requestedTimeout = 30.seconds),
        )
    }
}
