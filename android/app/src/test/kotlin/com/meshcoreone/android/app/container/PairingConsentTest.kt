// AndroidOnly: WP-303 Structured consent and cancellation through the production AppState pairing flow.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingConsentTest {
    @Test fun awaitingConsentNeverRemovesAnAssociationOrOpensThePicker() = runTest {
        val port = FakeConnectionPort()
        val leftover = SystemPairedAccessory(UUID.randomUUID(), "Radio")
        port.paired += leftover
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        assertNotNull(state.connectionUI.pendingSystemPairingSetup)
        assertFalse(pairing.isCompleted)
        assertNull(port.lastRemoved)
        assertEquals(0, port.pickerCalls)
        state.cancelSystemPairingSetup()
        assertFalse(pairing.await())
        assertEquals(listOf(leftover), port.paired)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun confirmationWaitsForThePickerAndCannotCompleteOrRemoveTwice() = runTest {
        val port = FakeConnectionPort()
        port.paired += SystemPairedAccessory(UUID.randomUUID(), "Radio")
        val picker = CompletableDeferred<Unit>()
        port.beforePicker = { picker.await() }
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        val confirmation = assertNotNull(state.confirmSystemPairingSetup())
        assertNull(state.confirmSystemPairingSetup())
        state.handleSystemPairingSetupSheetDismissed()
        runCurrent()
        assertFalse(pairing.isCompleted)
        assertTrue(port.isPairingFlowActive)
        assertEquals(1, port.pickerCalls)
        picker.complete(Unit)
        confirmation.join()
        assertTrue(pairing.await())
        assertTrue(port.paired.isEmpty())
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun cancellingWhileAwaitingConsentClearsThePromptAndPropagatesCancellation() = runTest {
        val port = FakeConnectionPort()
        val leftover = SystemPairedAccessory(UUID.randomUUID(), "Radio")
        port.paired += leftover
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        pairing.cancelAndJoin()
        assertTrue(pairing.isCancelled)
        assertNull(state.connectionUI.pendingSystemPairingSetup)
        assertFalse(port.isPairingFlowActive)
        assertEquals(listOf(leftover), port.paired)
        assertEquals(0, port.pickerCalls)
    }

    @Test fun cancellingAfterConfirmationCancelsTheOwnedPickerAndClearsBusyState() = runTest {
        val port = FakeConnectionPort()
        port.paired += SystemPairedAccessory(UUID.randomUUID(), "Radio")
        var pickerCancelled = false
        port.beforePicker = {
            try { CompletableDeferred<Unit>().await() }
            finally { pickerCancelled = true }
        }
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        val confirmation = assertNotNull(state.confirmSystemPairingSetup())
        runCurrent()
        pairing.cancelAndJoin()
        confirmation.join()
        assertTrue(pairing.isCancelled)
        assertTrue(confirmation.isCancelled)
        assertTrue(pickerCancelled)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(state.isConfirmingSystemPairingSetup)
        assertFalse(port.isPairingFlowActive)
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
    }

    @Test fun cancellingDeferredForegroundRetryDoesNotReopenThePicker() = runTest {
        val port = FakeConnectionPort()
        port.paired += SystemPairedAccessory(UUID.randomUUID(), "Radio")
        port.pickerOutcomes += { throw DevicePairingError.PickerUnavailable() }
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        state.confirmSystemPairingSetup()!!.join()
        assertTrue(state.connectionUI.shouldCompleteFreshPairingOnForeground)
        assertFalse(pairing.isCompleted)
        pairing.cancelAndJoin()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, port.pickerCalls)
        assertFalse(state.connectionUI.shouldCompleteFreshPairingOnForeground)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun cancellingTimedRetryWaitsForPickerCleanup() =
        cancellingRunningForegroundRetryWaitsForPickerCleanup(activate = false)

    @Test fun cancellingActivationRetryWaitsForPickerCleanup() =
        cancellingRunningForegroundRetryWaitsForPickerCleanup(activate = true)

    private fun cancellingRunningForegroundRetryWaitsForPickerCleanup(activate: Boolean) = runTest {
        val port = FakeConnectionPort()
        port.paired += SystemPairedAccessory(UUID.randomUUID(), "Radio")
        port.pickerOutcomes += { throw DevicePairingError.PickerUnavailable() }
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanupFinished = false
        port.beforePicker = {
            if (port.pickerCalls == 2) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        cleanupFinished = true
                    }
                }
            }
        }
        val state = appStateOf(backgroundScope, port)
        val pairing = async(UnconfinedTestDispatcher(testScheduler)) { state.startDeviceScanAndAwait() }
        runCurrent()
        state.confirmSystemPairingSetup()!!.join()
        if (activate) state.handleBecameActive() else testScheduler.advanceTimeBy(1_000)
        runCurrent()
        try {
            assertEquals(2, port.pickerCalls)
            assertFalse(pairing.isCompleted)
            pairing.cancel()
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertFalse(pairing.isCompleted)
            assertFalse(cleanupFinished)
        } finally {
            releaseCleanup.complete(Unit)
        }
        pairing.join()
        assertTrue(pairing.isCancelled)
        assertTrue(cleanupFinished)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(state.isFreshPairingForegroundRetry)
        assertFalse(state.connectionUI.shouldCompleteFreshPairingOnForeground)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun removalFailureIsVisibleAndDoesNotOpenThePickerOrReportSuccess() = runTest {
        val port = FakeConnectionPort()
        val leftover = SystemPairedAccessory(UUID.randomUUID(), "Radio")
        port.paired += leftover
        port.removeFailure = java.io.IOException("Association removal failed")
        val state = appStateOf(backgroundScope, port)
        val pairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        state.confirmSystemPairingSetup()!!.join()
        assertFalse(pairing.await())
        assertTrue(state.connectionUI.showingConnectionFailedAlert)
        assertNotNull(state.connectionUI.connectionFailedMessage)
        assertEquals(listOf(leftover), port.paired)
        assertEquals(0, port.pickerCalls)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun reentryCannotOpenThePickerBehindAnExistingConsentPrompt() = runTest {
        val port = FakeConnectionPort()
        port.paired += SystemPairedAccessory(UUID.randomUUID(), "Radio")
        val state = appStateOf(backgroundScope, port)
        state.startDeviceScan().join()
        val prompt = assertNotNull(state.connectionUI.pendingSystemPairingSetup)
        port.paired.clear()
        state.startDeviceScan().join()
        assertEquals(prompt, state.connectionUI.pendingSystemPairingSetup)
        assertEquals(0, port.pickerCalls)
        assertTrue(port.isPairingFlowActive)
    }
}
