package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The transient-retry ladder for section requests: a firmware "no response yet" is re-issued at the
 * ladder spacing inside one shared budget, every other error is terminal, and an exhausted budget
 * reports a timeout rather than looping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSectionRetryPolicyTest {
    private val noResponseYet =
        RemoteNodeError.SessionError(MeshError.DeviceError(RemoteSectionRetryPolicy.REMOTE_NODE_NO_RESPONSE_YET))

    @Test
    fun noResponseYetIsTransientAndOtherErrorsAreNot() {
        assertTrue(RemoteSectionRetryPolicy.isTransientError(noResponseYet))
        assertTrue(
            RemoteSectionRetryPolicy.isTransientError(
                MeshError.DeviceError(RemoteSectionRetryPolicy.REMOTE_NODE_NO_RESPONSE_YET),
            ),
        )
        assertFalse(RemoteSectionRetryPolicy.isTransientError(MeshError.DeviceError(3)))
        assertFalse(RemoteSectionRetryPolicy.isTransientError(RemoteNodeError.Timeout))
        assertFalse(RemoteSectionRetryPolicy.isTransientError(MeshError.Timeout))
        assertTrue(
            RemoteSectionRetryPolicy.isTransientError(
                MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=10"),
            ),
            "chat telemetry hits the JNI exception; the ladder must unwrap it",
        )
        assertFalse(
            RemoteSectionRetryPolicy.isTransientError(
                MeshBridgeException(BridgeErrorCode.TIMEOUT, "timed out"),
            ),
        )
    }

    @Test
    fun transientRepliesRetryOnTheLadderThenSucceed() = runTest {
        var attempts = 0
        val attemptTimes = mutableListOf<Long>()
        val result = RemoteSectionRetryPolicy.performWithTransientRetries { _ ->
            attemptTimes += testScheduler.currentTime
            attempts += 1
            if (attempts <= 3) throw noResponseYet
            "status"
        }

        assertEquals("status", result)
        assertEquals(4, attempts)
        // 0 / +500ms / +1s / +2s, the Swift ladder.
        assertContentEquals(listOf(0L, 500L, 1_500L, 3_500L), attemptTimes)
    }

    @Test
    fun eachAttemptGetsOnlyTheBudgetThatRemains() = runTest {
        val timeouts = mutableListOf<Duration>()
        var attempts = 0
        RemoteSectionRetryPolicy.performWithTransientRetries { timeout ->
            timeouts += timeout
            attempts += 1
            if (attempts == 1) throw noResponseYet
            "ok"
        }

        assertTrue(timeouts.first() <= RemoteOperationTimeoutPolicy.BINARY_MAXIMUM)
        assertTrue(timeouts[1] < timeouts[0], "second attempt must not restart the shared budget")
    }

    @Test
    fun nonTransientErrorPropagatesWithoutRetrying() = runTest {
        var attempts = 0
        assertFailsWith<RemoteNodeError.Timeout> {
            RemoteSectionRetryPolicy.performWithTransientRetries<String> { _ ->
                attempts += 1
                throw RemoteNodeError.Timeout
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun aCancelledAttemptPropagatesWithoutRetrying() = runTest {
        val cancelled = CancellationException("superseded")
        var attempts = 0
        val thrown = assertFailsWith<CancellationException> {
            RemoteSectionRetryPolicy.performWithTransientRetries<String> { _ ->
                attempts += 1
                throw cancelled
            }
        }
        assertSame(cancelled, thrown)
        assertEquals(1, attempts)
    }

    @Test
    fun anAttemptErrorPropagatesWithoutRetrying() = runTest {
        val probe = SectionRetryProbeError()
        var attempts = 0
        val thrown = assertFailsWith<SectionRetryProbeError> {
            RemoteSectionRetryPolicy.performWithTransientRetries<String> { _ ->
                attempts += 1
                throw probe
            }
        }
        assertSame(probe, thrown)
        assertEquals(1, attempts)
    }

    @Test
    fun exhaustedLadderRethrowsTheTransientError() = runTest {
        var attempts = 0
        assertFailsWith<RemoteNodeError.SessionError> {
            RemoteSectionRetryPolicy.performWithTransientRetries<String> { _ ->
                attempts += 1
                throw noResponseYet
            }
        }
        assertEquals(RemoteSectionRetryPolicy.TRANSIENT_RETRY_DELAYS.size + 1, attempts)
    }

    @Test
    fun exhaustedBudgetReportsTimeout() = runTest {
        val clock = TestTimeSource()
        var attempts = 0
        assertFailsWith<RemoteNodeError.Timeout> {
            RemoteSectionRetryPolicy.performWithTransientRetries(budget = 10.seconds, timeSource = clock) { _ ->
                attempts += 1
                clock += 6.seconds
                throw noResponseYet
            }
        }
        // Two attempts fit in the budget; the third finds it spent.
        assertEquals(2, attempts)
    }
}

private class SectionRetryProbeError : Error("probe")
