package one.meshcore.shared.settings.ui

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryAlertStateTest {
    @Test
    fun showIncrementsAndPresents() {
        val state = RetryAlertState()
        state.show("boom", onRetry = {}, onMaxRetriesExceeded = {})
        assertTrue(state.isPresented)
        assertEquals("boom", state.message)
        assertEquals(1, state.retryCount)
        assertFalse(state.isMaxRetriesExceeded)
    }

    @Test
    fun thirdShowReachesMaxAndAcknowledgeRunsHandler() {
        val state = RetryAlertState()
        var dismissed = false
        repeat(3) { state.show("boom", onRetry = {}, onMaxRetriesExceeded = { dismissed = true }) }
        assertTrue(state.isMaxRetriesExceeded)
        state.acknowledgeMax()
        assertTrue(dismissed)
        assertEquals(0, state.retryCount)
        assertFalse(state.isPresented)
    }

    @Test
    fun retryInvokesCallbackAndHidesAlert() {
        val state = RetryAlertState()
        var retried = false
        state.show("boom", onRetry = { retried = true }, onMaxRetriesExceeded = {})
        state.retry()
        assertTrue(retried)
        assertFalse(state.isPresented)
    }

    @Test
    fun resetClearsCount() {
        val state = RetryAlertState()
        state.show("boom", onRetry = {}, onMaxRetriesExceeded = {})
        state.reset()
        assertEquals(0, state.retryCount)
        assertFalse(state.isPresented)
    }

    @Test
    fun retryableClassification() {
        assertTrue(MeshBridgeException(BridgeErrorCode.TIMEOUT, "t").isRetryableSettingsError())
        assertTrue(MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "t").isRetryableSettingsError())
        assertTrue(MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "t").isRetryableSettingsError())
        assertTrue(IllegalStateException("No radio connected").isRetryableSettingsError())
        assertFalse(MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "t").isRetryableSettingsError())
        assertFalse(IllegalArgumentException("bad").isRetryableSettingsError())
    }
}
