package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KableObserveFailureTest {
    @Test
    fun aCancelledObserveDoesNotMapTheFailure() = runTest {
        val cancelled = CancellationException("stopped")
        var mapped = false
        val thrown = assertFailsWith<CancellationException> {
            runKableObserved(onFailure = {
                mapped = true
                throw it
            }) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertFalse(mapped)
    }

    @Test
    fun anObserveFailureCanBecomeANotifyMiss() = runTest {
        val cause = IllegalStateException("notify off")
        val thrown = assertFailsWith<NotificationsNotEnabledException> {
            runKableObserved(onFailure = { throw rxNotifyFailure(it, holdEmptyGatt = true) }) {
                throw cause
            }
        }
        assertSame(cause, thrown.cause)
        assertEquals("notify off", thrown.message)
    }

    @Test
    fun anObserveFailureWithoutAHoldKeepsTheOriginalError() = runTest {
        val cause = IllegalStateException("notify off")
        val thrown = assertFailsWith<IllegalStateException> {
            runKableObserved(onFailure = { throw rxNotifyFailure(it, holdEmptyGatt = false) }) {
                throw cause
            }
        }
        assertSame(cause, thrown)
    }

    @Test
    fun anObserveErrorIsMapped() = runTest {
        val probe = KableObserveProbeError()
        var seen: Throwable? = null
        val thrown = assertFailsWith<KableObserveProbeError> {
            runKableObserved(
                onFailure = { error ->
                    seen = error
                    throw error
                },
            ) { throw probe }
        }
        assertSame(probe, thrown)
        assertSame(probe, seen)
        assertTrue(rxNotifyFailure(probe, holdEmptyGatt = false) === probe)
    }
}

private class KableObserveProbeError : Error("probe")
