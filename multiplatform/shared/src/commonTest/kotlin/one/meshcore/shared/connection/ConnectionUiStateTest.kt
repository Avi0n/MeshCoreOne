package one.meshcore.shared.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.transport.BondLostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors the iOS `ConnectionUIStateTests` and `DisconnectedPillTests`. The auto-hide timers run on the
 * test's virtual clock via [runTest]'s scheduler, so timing is exact and instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionUiStateTest {
    // MARK: Ready toast

    @Test
    fun showReadyToastBrieflySetsAndAutoHidesAfterTwoSeconds() = runTest {
        val ui = ConnectionUiState(this)
        ui.showReadyToastBriefly()
        assertTrue(ui.showReadyToast.value)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(ui.showReadyToast.value)
    }

    @Test
    fun hideReadyToastClearsImmediately() = runTest {
        val ui = ConnectionUiState(this)
        ui.showReadyToastBriefly()
        ui.hideReadyToast()
        assertFalse(ui.showReadyToast.value)
    }

    @Test
    fun callingShowReadyToastBrieflyAgainResetsTheTimer() = runTest {
        val ui = ConnectionUiState(this)
        ui.showReadyToastBriefly()
        advanceTimeBy(1.5.seconds)
        runCurrent()

        ui.showReadyToastBriefly()
        assertTrue(ui.showReadyToast.value)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(ui.showReadyToast.value, "the reset timer has not elapsed yet")
    }

    // MARK: Sync-failed pill

    @Test
    fun showSyncFailedPillSetsAndAutoHidesAfterSevenSeconds() = runTest {
        val ui = ConnectionUiState(this)
        ui.showSyncFailedPill()
        assertTrue(ui.syncFailedPillVisible.value)

        advanceTimeBy(7.seconds)
        runCurrent()
        assertFalse(ui.syncFailedPillVisible.value)
    }

    @Test
    fun hideSyncFailedPillClearsImmediately() = runTest {
        val ui = ConnectionUiState(this)
        ui.showSyncFailedPill()
        ui.hideSyncFailedPill()
        assertFalse(ui.syncFailedPillVisible.value)
    }

    // MARK: Disconnected pill

    @Test
    fun disconnectedPillShownAfterOneSecondOnUnexpectedDisconnect() = runTest {
        val ui = ConnectionUiState(this)
        ui.updateDisconnected(lastId = "device", suppress = false)
        assertFalse(ui.disconnectedPillVisible.value, "hidden during the 1s debounce")

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(ui.disconnectedPillVisible.value)
    }

    @Test
    fun disconnectedPillNotShownWhenUserExplicitlyDisconnected() = runTest {
        val ui = ConnectionUiState(this)
        ui.updateDisconnected(lastId = "device", suppress = true)
        advanceUntilIdle()
        assertFalse(ui.disconnectedPillVisible.value)
    }

    @Test
    fun disconnectedPillNotShownWithoutALastConnectedDevice() = runTest {
        val ui = ConnectionUiState(this)
        ui.updateDisconnected(lastId = null, suppress = false)
        advanceUntilIdle()
        assertFalse(ui.disconnectedPillVisible.value)
    }

    @Test
    fun disconnectedPillHiddenWhenConnectionStarts() = runTest {
        val ui = ConnectionUiState(this)
        ui.updateDisconnected(lastId = "device", suppress = false)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(ui.disconnectedPillVisible.value)

        ui.hideDisconnectedPill()
        assertFalse(ui.disconnectedPillVisible.value)
    }

    @Test
    fun disconnectedPillDelayPreventsFlashDuringBriefReconnects() = runTest {
        val ui = ConnectionUiState(this)
        ui.updateDisconnected(lastId = "device", suppress = false)
        assertFalse(ui.disconnectedPillVisible.value)

        advanceTimeBy(0.5.seconds)
        runCurrent()
        ui.hideDisconnectedPill()

        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(ui.disconnectedPillVisible.value)
    }

    // MARK: Sync activity

    @Test
    fun multipleSyncActivitiesKeepTheCountUntilAllEnd() = runTest {
        val ui = ConnectionUiState(this)
        ui.syncStarted()
        ui.syncStarted()
        assertEquals(2, ui.syncActivityCount.value)

        ui.syncEnded(succeeded = false)
        assertEquals(1, ui.syncActivityCount.value)
        ui.syncEnded(succeeded = false)
        assertEquals(0, ui.syncActivityCount.value)
    }

    @Test
    fun syncEndedGuardsAgainstDoubleDecrementBelowZero() = runTest {
        val ui = ConnectionUiState(this)
        ui.syncStarted()
        ui.syncEnded(succeeded = false)
        ui.syncEnded(succeeded = false)
        assertEquals(0, ui.syncActivityCount.value, "the extra end is ignored, not negative")
    }

    @Test
    fun completingAllSyncActivitySuccessfullyShowsTheReadyToast() = runTest {
        val ui = ConnectionUiState(this)
        ui.syncStarted()
        ui.syncEnded(succeeded = true)
        assertTrue(ui.showReadyToast.value)
    }

    @Test
    fun completingSyncUnsuccessfullyDoesNotShowTheReadyToast() = runTest {
        val ui = ConnectionUiState(this)
        ui.syncStarted()
        ui.syncEnded(succeeded = false)
        assertFalse(ui.showReadyToast.value)
    }

    // MARK: handleDisconnect

    @Test
    fun handleDisconnectResetsAllStateInASingleCall() = runTest {
        val ui = ConnectionUiState(this)
        ui.syncStarted()
        ui.syncStarted()
        ui.currentSyncPhase = SyncPhase.CHANNELS
        ui.isNodeStorageFull = true
        ui.showReadyToastBriefly()

        ui.handleDisconnect(DeviceConnectionState.DISCONNECTED, null, shouldSuppressDisconnectedPill = false)

        assertEquals(0, ui.syncActivityCount.value)
        assertNull(ui.currentSyncPhase)
        assertFalse(ui.isNodeStorageFull)
        assertFalse(ui.showReadyToast.value)
    }

    @Test
    fun handleDisconnectShowsTheDisconnectedPillWhenDeviceWasPaired() = runTest {
        val ui = ConnectionUiState(this)
        ui.handleDisconnect(DeviceConnectionState.DISCONNECTED, "device", shouldSuppressDisconnectedPill = false)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(ui.disconnectedPillVisible.value)
    }

    @Test
    fun handleDisconnectDoesNotShowTheDisconnectedPillWhenSuppressed() = runTest {
        val ui = ConnectionUiState(this)
        ui.handleDisconnect(DeviceConnectionState.DISCONNECTED, "device", shouldSuppressDisconnectedPill = true)
        advanceUntilIdle()
        assertFalse(ui.disconnectedPillVisible.value)
    }

    // MARK: Alert routing

    @Test
    fun defaultsHaveNoAlertAndAreNotBusy() = runTest {
        val ui = ConnectionUiState(this)
        assertNull(ui.connectionAlert.value)
        assertFalse(ui.isBusy)
        assertFalse(ui.isNodeStorageFull)
    }

    @Test
    fun presentPairingFailureAuthenticationSetsTheAuthenticationKind() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentPairingFailure(PairingFailure.ConnectionFailed("device", isAuthenticationFailure = true))

        val alert = assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)
        assertEquals("device", alert.deviceId)
        assertEquals(PairingFailureKind.AUTHENTICATION, alert.kind)
        assertTrue(alert.kind.requiresBondRemoval)
    }

    @Test
    fun presentPairingFailureTransientSetsTheTransientKind() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentPairingFailure(PairingFailure.ConnectionFailed("device", isAuthenticationFailure = false))

        val alert = assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)
        assertEquals(PairingFailureKind.TRANSIENT, alert.kind)
        assertFalse(alert.kind.requiresBondRemoval, "a transient failure must keep the non-destructive path")
    }

    @Test
    fun freshPairingAuthenticationFailureIsPinRejected() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentFreshPairingFailure(PairingFailure.ConnectionFailed("device", isAuthenticationFailure = true))

        val alert = assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)
        assertEquals(PairingFailureKind.PIN_REJECTED, alert.kind)
        assertTrue(alert.kind.requiresBondRemoval)
    }

    @Test
    fun presentConnectionFailureClearsPairingStateSetByAPriorPairingFailure() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentPairingFailure(PairingFailure.ConnectionFailed("device", isAuthenticationFailure = true))
        assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)

        ui.presentConnectionFailure("generic")

        val alert = assertIs<ConnectionAlert.Generic>(ui.connectionAlert.value)
        assertEquals("generic", alert.message)
    }

    @Test
    fun savedDeviceAuthenticationFailureRoutesToGuidedRePair() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentSavedDeviceConnectFailure("device", SavedDeviceConnectError.AuthenticationFailed)

        val alert = assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)
        assertEquals(PairingFailureKind.AUTHENTICATION, alert.kind)
    }

    @Test
    fun otherAppFailuresRouteToTheOtherAppAlert() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentPairingFailure(PairingFailure.DeviceConnectedToOtherApp("device"))
        assertIs<ConnectionAlert.OtherApp>(ui.connectionAlert.value)

        ui.presentSavedDeviceConnectFailure("device2", SavedDeviceConnectError.OtherApp)
        assertEquals("device2", assertIs<ConnectionAlert.OtherApp>(ui.connectionAlert.value).deviceId)
    }

    @Test
    fun classifySavedDeviceConnectErrorMatchesIosPresentSavedDeviceConnectFailure() {
        assertEquals(
            SavedDeviceConnectError.OtherApp,
            classifySavedDeviceConnectError(DeviceConnectedToOtherAppException()),
        )
        assertEquals(
            SavedDeviceConnectError.AuthenticationFailed,
            classifySavedDeviceConnectError(BondLostException("bond")),
        )
        assertEquals(
            SavedDeviceConnectError.Other(null),
            classifySavedDeviceConnectError(IllegalStateException("kable")),
        )
    }

    @Test
    fun dismissAlertClearsIt() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentConnectionFailure("boom")
        ui.dismissAlert()
        assertNull(ui.connectionAlert.value)
    }

    @Test
    fun clearPairingFailureDropsOnlyAPairingAlert() = runTest {
        val ui = ConnectionUiState(this)
        ui.presentPairingFailure(PairingFailure.ConnectionFailed("device", isAuthenticationFailure = true))
        ui.clearPairingFailure()
        assertNull(ui.connectionAlert.value)

        ui.presentConnectionFailure("generic")
        ui.clearPairingFailure()
        val leftover = assertIs<ConnectionAlert.Generic>(ui.connectionAlert.value)
        assertEquals("generic", leftover.message)
    }

    @Test
    fun removeAndRetryRequestsTheConnectPickerUntilConsumed() = runTest {
        val ui = ConnectionUiState(this)
        assertFalse(ui.shouldShowConnectPicker.value)
        ui.requestConnectPicker()
        assertTrue(ui.shouldShowConnectPicker.value)
        ui.consumeConnectPicker()
        assertFalse(ui.shouldShowConnectPicker.value)
    }

    @Test
    fun removeAndRetryStartsCompanionPairingOnceTheSheetConsumesIt() = runTest {
        val ui = ConnectionUiState(this)
        assertFalse(ui.consumeCompanionPairingOnPicker())
        ui.requestConnectPicker()
        assertTrue(ui.consumeCompanionPairingOnPicker())
        assertFalse(ui.consumeCompanionPairingOnPicker())
    }

    private fun ConnectionUiState.updateDisconnected(lastId: String?, suppress: Boolean) = updateDisconnectedPillState(
        DeviceConnectionState.DISCONNECTED,
        lastId,
        shouldSuppressDisconnectedPill = suppress,
    )
}
