package one.meshcore.shared.ui

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.connection.SavedDeviceConnectError
import one.meshcore.shared.connection.classifySavedDeviceConnectError
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_ble_bluetooth_unauthorized
import one.meshcore.shared.resources.localizable_error_mesh_core_bluetooth_unauthorized
import one.meshcore.shared.settings.ui.settingsMessageFormatArgs
import one.meshcore.shared.settings.ui.settingsMessageResource
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.transport.BondLostException
import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothUnauthorizedPresentationTest {
    @Test
    fun bridgeAuthorizationFailureUsesTheSameAlertAndItsExistingSettingsRemedy() = runTest {
        val error = MeshBridgeException(BridgeErrorCode.BLUETOOTH_UNAUTHORIZED, "private attribution detail")
        val ui = ConnectionUiState(backgroundScope)
        ui.presentSavedDeviceConnectFailure(HANDLE, classifySavedDeviceConnectError(error))
        assertEquals(ConnectionAlert.BluetoothUnauthorized, ui.connectionAlert.value)
        assertEquals(Res.string.localizable_error_mesh_core_bluetooth_unauthorized, error.settingsMessageResource())
        assertEquals(emptyList(), error.settingsMessageFormatArgs())
    }

    @Test
    fun deniedBluetoothKeepsThePermissionRemedyInsteadOfRepairingTheWrappedBond() = runTest {
        val error = BluetoothUnauthorizedException(BondLostException("untranslated native detail"))
        val ui = ConnectionUiState(backgroundScope)
        ui.presentSavedDeviceConnectFailure(HANDLE, classifySavedDeviceConnectError(error))
        assertEquals(ConnectionAlert.BluetoothUnauthorized, ui.connectionAlert.value)
        ui.dismissAlert()
        ui.presentSavedDeviceConnectFailure(HANDLE, classifySavedDeviceConnectError(BondLostException("bond")))
        assertEquals(ConnectionAlert.Pairing(HANDLE, PairingFailureKind.AUTHENTICATION), ui.connectionAlert.value)
        ui.dismissAlert()
        ui.presentSavedDeviceConnectFailure(
            HANDLE,
            classifySavedDeviceConnectError(IllegalStateException("native detail")),
        )
        assertEquals(ConnectionAlert.Generic(null), ui.connectionAlert.value)
    }

    @Test
    fun settingsPermissionRemedyDoesNotInterpolateNativeExceptionDetails() {
        val error = BluetoothUnauthorizedException(IllegalStateException("private attribution detail"))
        assertEquals(Res.string.localizable_error_ble_bluetooth_unauthorized, error.settingsMessageResource())
        assertEquals(emptyList(), error.settingsMessageFormatArgs())
        assertEquals(SavedDeviceConnectError.BluetoothUnauthorized, classifySavedDeviceConnectError(error))
    }

    private companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:FF"
    }
}
