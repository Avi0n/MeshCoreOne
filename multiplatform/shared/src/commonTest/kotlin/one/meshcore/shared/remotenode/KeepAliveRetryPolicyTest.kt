package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors the iOS `RemoteNodeKeepAliveTests`: every branch of the keep-alive classifier plus the
 * failure-reason strings.
 */
class KeepAliveRetryPolicyTest {
    @Test
    fun singleTransientFailureRetries() {
        val eval = KeepAliveRetryPolicy.evaluate(
            RemoteNodeError.SessionError(MeshError.Timeout),
            consecutiveFailures = 0,
        )
        assertEquals(1, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, eval.action)
    }

    @Test
    fun twoConsecutiveTransientFailuresDisconnect() {
        val eval = KeepAliveRetryPolicy.evaluate(
            RemoteNodeError.SessionError(MeshError.Timeout),
            consecutiveFailures = 1,
        )
        assertEquals(2, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT, eval.action)
    }

    @Test
    fun sessionNotFoundDisconnectsNowWithoutIncrement() {
        val eval = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionNotFound, consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, eval.action)
    }

    @Test
    fun contactNotFoundDisconnectsNowWithoutIncrement() {
        val eval = KeepAliveRetryPolicy.evaluate(RemoteNodeError.ContactNotFound, consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, eval.action)
    }

    @Test
    fun deviceErrorIsTransient() {
        val eval = KeepAliveRetryPolicy.evaluate(
            RemoteNodeError.SessionError(MeshError.DeviceError(code = 7)),
            consecutiveFailures = 0,
        )
        assertEquals(1, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, eval.action)
    }

    @Test
    fun notConnectedIsTransient() {
        val eval = KeepAliveRetryPolicy.evaluate(
            RemoteNodeError.SessionError(MeshError.NotConnected),
            consecutiveFailures = 0,
        )
        assertEquals(1, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, eval.action)
    }

    @Test
    fun floodRoutedIsSkippedNotCounted() {
        val eval = KeepAliveRetryPolicy.evaluate(RemoteNodeError.FloodRouted, consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.SKIP, eval.action)
    }

    @Test
    fun cancellationExceptionStops() {
        val eval = KeepAliveRetryPolicy.evaluate(CancellationException("cancelled"), consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.STOP, eval.action)
    }

    @Test
    fun remoteNodeCancelledStops() {
        val eval = KeepAliveRetryPolicy.evaluate(RemoteNodeError.Cancelled, consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.STOP, eval.action)
    }

    @Test
    fun unknownErrorDisconnectsNow() {
        val eval = KeepAliveRetryPolicy.evaluate(IllegalStateException("persistence"), consecutiveFailures = 0)
        assertEquals(0, eval.consecutiveFailures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, eval.action)
    }

    @Test
    fun shouldExitLoopMatchesAction() {
        assertEquals(false, KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL.shouldExitLoop)
        assertEquals(false, KeepAliveRetryPolicy.Action.SKIP.shouldExitLoop)
        assertEquals(true, KeepAliveRetryPolicy.Action.STOP.shouldExitLoop)
        assertEquals(true, KeepAliveRetryPolicy.Action.DISCONNECT.shouldExitLoop)
        assertEquals(true, KeepAliveRetryPolicy.Action.DISCONNECT_NOW.shouldExitLoop)
    }

    @Test
    fun failureReasonStrings() {
        assertEquals("timeout", KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshError.Timeout)))
        assertEquals(
            "device error (code: 42)",
            KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshError.DeviceError(42))),
        )
        assertEquals(
            "transport not connected",
            KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshError.NotConnected)),
        )
        assertEquals("session not found", KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionNotFound))
        assertEquals("contact not found", KeepAliveRetryPolicy.failureReason(RemoteNodeError.ContactNotFound))
    }
}
