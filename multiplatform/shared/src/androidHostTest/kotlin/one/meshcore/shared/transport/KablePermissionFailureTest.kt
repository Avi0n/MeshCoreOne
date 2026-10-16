package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class KablePermissionFailureTest {
    @Test
    fun aNativePermissionDenialOutranksStaleBondAndEncryptionStatuses() = runTest {
        for (status in listOf(DEAD_BOND_STATUS, ENCRYPTION_TIMEOUT_STATUS)) {
            val native = SecurityException("BLUETOOTH_CONNECT denied")
            var statusReads = 0
            val thrown = assertFailsWith<BluetoothUnauthorizedException> {
                runKableConnect(disconnectedGattStatus = {
                    statusReads++
                    status
                }) {
                    throw native
                }
            }
            assertSame(native, thrown.cause)
            assertEquals(0, statusReads, "Denied authorization must not read another permission-protected GATT status")
        }
    }

    @Test
    fun anUnauthorizedNotificationCannotBecomeAnEmptyGattRetry() {
        val native = SecurityException("BLUETOOTH_CONNECT revoked")
        val mapped = assertIs<BluetoothUnauthorizedException>(rxNotifyFailure(native, holdEmptyGatt = true))
        assertSame(native, mapped.cause)
        assertSame(mapped, rxNotifyFailure(mapped, holdEmptyGatt = true))
    }

    @Test
    fun notificationCancellationRetainsItsOriginalIdentityDuringGattHold() {
        val stopped = CancellationException("stopped")
        assertSame(stopped, rxNotifyFailure(stopped, holdEmptyGatt = true))
    }

    @Test
    fun deniedAuthorizationStopsBeforeHandleResolutionAndAGrantReachesResolution() = runTest {
        var authorized = false
        var permissionReads = 0
        val transport = KableBleTransport(
            scope = backgroundScope,
            deviceId = "",
            options = KableBleTransport.Options(
                bluetoothConnectAuthorized = {
                    permissionReads++
                    authorized
                },
            ),
        )

        assertFailsWith<BluetoothUnauthorizedException> { transport.connect() }
        assertFalse(transport.isConnected)
        assertEquals(1, permissionReads)

        authorized = true
        val invalidHandle = assertFailsWith<IllegalArgumentException> { transport.connect() }
        assertEquals("KableBleTransport requires a non-blank deviceId", invalidHandle.message)
        assertEquals(2, permissionReads)
        assertFalse(transport.isConnected)
        transport.disconnect()
    }

    private companion object {
        const val DEAD_BOND_STATUS = 5
        const val ENCRYPTION_TIMEOUT_STATUS = 8
    }
}
