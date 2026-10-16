package one.meshcore.shared.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan-start and availability rules both BLE pickers share: the onboarding Pair step and the
 * post-onboarding connect sheet. Without the permission request a first-run scan returns nothing on API
 * 31+, and without the remedy classification an unusable adapter renders as an endless spinner.
 */
class BleScanAvailabilityTest {
    @Test
    fun aScanWithoutTheBluetoothGrantRequestsItFirst() {
        val permissions = RecordingPermissionController(PermissionUiState())
        val scan = RecordingBleScanController()

        startBleScanRequestingPermission(permissions, scan)

        assertEquals(listOf(OnboardingPermission.BLUETOOTH), permissions.requested)
        assertEquals(1, scan.startCount)
    }

    @Test
    fun anAlreadyGrantedScanDoesNotReprompt() {
        val granted = PermissionUiState(
            granted = setOf(OnboardingPermission.BLUETOOTH),
            determined = setOf(OnboardingPermission.BLUETOOTH),
        )
        val permissions = RecordingPermissionController(granted)
        val scan = RecordingBleScanController()

        startBleScanRequestingPermission(permissions, scan)

        assertTrue(permissions.requested.isEmpty())
        assertEquals(1, scan.startCount)
    }

    @Test
    fun aReadyAdapterNeedsNoRemedy() {
        assertNull(bleScanRemedy(BleAvailability.READY))
    }

    @Test
    fun prepareScannedDeviceConnectStopsTheScanAndRejectsWhileBusy() {
        val scan = RecordingBleScanController()
        assertTrue(prepareScannedDeviceConnect(connecting = false, stopScan = scan::stopScan))
        assertEquals(1, scan.stopCount)
        assertFalse(prepareScannedDeviceConnect(connecting = true, stopScan = scan::stopScan))
        assertEquals(1, scan.stopCount)
    }

    @Test
    fun anUnusableAdapterNamesItsRemedy() {
        assertEquals(BleScanRemedy.POWER_ON_BLUETOOTH, bleScanRemedy(BleAvailability.POWERED_OFF))
        assertEquals(BleScanRemedy.GRANT_BLUETOOTH_PERMISSION, bleScanRemedy(BleAvailability.UNAUTHORIZED))
    }

    private class RecordingPermissionController(initial: PermissionUiState) : PermissionController {
        override val state = MutableStateFlow(initial)
        val requested = mutableListOf<OnboardingPermission>()

        override fun request(permission: OnboardingPermission) {
            requested += permission
        }

        override fun refresh() = Unit
    }

    private class RecordingBleScanController : BleScanController {
        override val availability: StateFlow<BleAvailability> = MutableStateFlow(BleAvailability.READY)
        override val devices: StateFlow<List<ScannedDevice>> = MutableStateFlow(emptyList())
        var startCount = 0
            private set
        var stopCount = 0
            private set

        override fun startScan() {
            startCount++
        }

        override fun stopScan() {
            stopCount++
        }
    }
}
