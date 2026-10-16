package one.meshcore.shared.ui

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bond-removal recovery has to find the saved row for the radio the alert names. A pairing failure
 * carries the volatile transport handle, so treating that handle as a row id deletes nothing and leaves
 * the responder holding a dead bond with a button that appears to work.
 */
class ConnectionAlertRecoveryTest {
    @Test
    fun aTransportHandleResolvesToItsSavedRow() = runTest {
        val actions = SavedDevicesActions(listOf(savedDevice()))

        assertEquals(ROW_ID, savedDeviceRowId(actions, HANDLE))
    }

    @Test
    fun aRowIdStillResolvesForARadioSavedWithoutAHandle() = runTest {
        val actions = SavedDevicesActions(listOf(savedDevice(bleHandle = null)))

        assertEquals(ROW_ID, savedDeviceRowId(actions, ROW_ID.value))
    }

    @Test
    fun anUnknownRadioResolvesToNothingRatherThanADeletableGuess() = runTest {
        val actions = SavedDevicesActions(listOf(savedDevice()))

        assertNull(savedDeviceRowId(actions, "F0:0F:00:00:00:01"))
    }

    private fun savedDevice(bleHandle: String? = HANDLE) = SavedRadioDevice(
        id = ROW_ID,
        name = "Radio",
        wifi = null,
        bleHandle = bleHandle,
        lastConnected = 0.0,
    )

    private class SavedDevicesActions(private val saved: List<SavedRadioDevice>) : ConnectionActions {
        override suspend fun connect(deviceId: String) = Unit

        override suspend fun connectViaWiFi(host: String, port: Int) = Unit

        override suspend fun connectSimulator() = Unit

        override suspend fun listSavedDevices(): List<SavedRadioDevice> = saved

        override suspend fun disconnect() = Unit

        override suspend fun sendAdvert(flood: Boolean) = one.meshcore.shared.navigation.SendAdvertShortcutOutcome.Sent

        override fun dismissAlert() = Unit
    }

    private companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:FF"
        val ROW_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    }
}
