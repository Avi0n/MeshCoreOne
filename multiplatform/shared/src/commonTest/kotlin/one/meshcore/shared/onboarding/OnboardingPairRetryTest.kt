package one.meshcore.shared.onboarding

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.RecordingHapticFeedback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** iOS `DeviceScanView` pins Retry Connection after an other-app failure. */
class OnboardingPairRetryTest {
    @Test
    fun anOtherAppAlertLatchesTheDeviceId() {
        assertEquals(
            "radio-1",
            latchedOtherAppDeviceId(current = null, incoming = ConnectionAlert.OtherApp("radio-1")),
        )
    }

    @Test
    fun dismissingTheAlertKeepsTheLatchedId() {
        assertEquals(
            "radio-1",
            latchedOtherAppDeviceId(current = "radio-1", incoming = null),
        )
    }

    @Test
    fun aGenericFailureDoesNotReplaceTheLatch() {
        assertEquals(
            "radio-1",
            latchedOtherAppDeviceId(
                current = "radio-1",
                incoming = ConnectionAlert.Generic("boom"),
            ),
        )
    }

    @Test
    fun retryBeatsAddDeviceButNotSimulatorOrDemo() {
        assertTrue(
            pairPrimaryUsesRetryConnection(
                otherAppDeviceId = "radio-1",
                supportsSimulatorConnect = false,
                demoEnabled = false,
            ),
        )
        assertFalse(
            pairPrimaryUsesRetryConnection(
                otherAppDeviceId = "radio-1",
                supportsSimulatorConnect = true,
                demoEnabled = false,
            ),
        )
        assertFalse(
            pairPrimaryUsesRetryConnection(
                otherAppDeviceId = "radio-1",
                supportsSimulatorConnect = false,
                demoEnabled = true,
            ),
        )
        assertFalse(
            pairPrimaryUsesRetryConnection(
                otherAppDeviceId = null,
                supportsSimulatorConnect = false,
                demoEnabled = false,
            ),
        )
        assertTrue(pairPrimaryUsesSimulatorConnect(supportsSimulatorConnect = true, demoEnabled = false))
        assertTrue(pairPrimaryUsesSimulatorConnect(supportsSimulatorConnect = false, demoEnabled = true))
        assertFalse(pairPrimaryUsesSimulatorConnect(supportsSimulatorConnect = false, demoEnabled = false))
        assertNull(
            latchedOtherAppDeviceId(current = null, incoming = ConnectionAlert.Generic("boom")),
        )
    }

    @Test
    fun aSuccessfulConnectPlaysTheSuccessHaptic() = runTest {
        val recorder = RecordingHapticFeedback()
        assertTrue(runConnect(AppHaptics(recorder)) { })
        assertEquals(listOf(HapticFeedbackType.Confirm), recorder.types)
    }

    @Test
    fun aFailedConnectPlaysTheFailureHapticAndReturnsFalse() = runTest {
        val recorder = RecordingHapticFeedback()
        assertFalse(runConnect(AppHaptics(recorder)) { error("link down") })
        assertEquals(listOf(HapticFeedbackType.Reject), recorder.types)
    }

    @Test
    fun aCancelledConnectPropagatesWithoutAFailureHaptic() = runTest {
        val recorder = RecordingHapticFeedback()
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            runConnect(AppHaptics(recorder)) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertEquals(emptyList(), recorder.types)
    }

    @Test
    fun aConnectErrorPropagatesWithoutAFailureHaptic() = runTest {
        val recorder = RecordingHapticFeedback()
        assertFailsWith<ConnectProbeError> {
            runConnect(AppHaptics(recorder)) { throw ConnectProbeError() }
        }
        assertEquals(emptyList(), recorder.types)
    }
}

private class ConnectProbeError : Error("probe")
