package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Remove-and-retry and try-again close the pairing alert after the action. A failed action still
 * closes it. Cancellation and an error leave it open.
 */
class PairingAlertActionTest {
    @Test
    fun aFinishedActionDismissesTheAlert() = runTest {
        var ran = false
        var dismissed = false
        runPairingAlertAction(onDismiss = { dismissed = true }) {
            ran = true
        }
        assertTrue(ran)
        assertTrue(dismissed)
    }

    @Test
    fun aFailedActionStillDismissesTheAlert() = runTest {
        var steps = 0
        var dismissed = false
        runPairingAlertAction(onDismiss = { dismissed = true }) {
            steps += 1
            error("cleanup failed")
        }
        assertEquals(1, steps)
        assertTrue(dismissed)
    }

    @Test
    fun aCancelledActionLeavesTheAlertOpen() = runTest {
        var dismissed = false
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            runPairingAlertAction(onDismiss = { dismissed = true }) {
                throw cancelled
            }
        }
        assertSame(cancelled, thrown)
        assertFalse(dismissed)
    }

    @Test
    fun anActionErrorLeavesTheAlertOpen() = runTest {
        var dismissed = false
        val probe = PairingAlertProbeError()
        val thrown = assertFailsWith<PairingAlertProbeError> {
            runPairingAlertAction(onDismiss = { dismissed = true }) {
                throw probe
            }
        }
        assertSame(probe, thrown)
        assertFalse(dismissed)
    }
}

private class PairingAlertProbeError : Error("probe")
