package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunSettingsFetchTest {
    @Test
    fun successReturnsFalseAfterTheBlockRuns() = runTest {
        var ran = false

        val timedOut = runSettingsFetch { ran = true }

        assertTrue(ran)
        assertFalse(timedOut)
    }

    @Test
    fun timeoutReturnsTrue() = runTest {
        assertTrue(runSettingsFetch { throw RemoteNodeError.Timeout })
    }

    @Test
    fun otherRemoteNodeErrorReturnsFalse() = runTest {
        assertFalse(runSettingsFetch { throw RemoteNodeError.NotConnected })
    }

    @Test
    fun cancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            runSettingsFetch { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun unrelatedFailurePropagatesUnchanged() = runTest {
        val failure = IllegalStateException("parse")

        val thrown = assertFailsWith<IllegalStateException> {
            runSettingsFetch { throw failure }
        }

        assertSame(failure, thrown)
    }
}
