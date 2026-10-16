package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure GATT-status-to-connect-exception mapping. The encryption-timeout-class statuses become an
 * [EncryptionTimeoutException] the reconnect policy tallies toward bond-suspect escalation; every other
 * status stays a plain transient failure (null), so the caller keeps the original error.
 */
class GattConnectFailureTest {
    @Test
    fun encryptionTimeoutClassStatusesMapToEncryptionTimeout() {
        for (status in listOf(8, 22, 133)) {
            assertTrue(
                encryptionTimeoutForGattStatus(status) is EncryptionTimeoutException,
                "status $status should map to EncryptionTimeoutException",
            )
        }
    }

    @Test
    fun definitiveBondStatusesMapToBondLost() {
        for (status in listOf(5, 15, 137)) {
            assertTrue(
                classifyGattConnectFailure(status) is BondLostException,
                "status $status should map to BondLostException",
            )
        }
    }

    @Test
    fun ordinaryStatusesDoNotMap() {
        for (status in listOf(0, 1, 19, 62)) {
            assertNull(
                classifyGattConnectFailure(status),
                "status $status should not map to a classified connect failure",
            )
        }
    }

    @Test
    fun dropReasonMapsDefinitiveBondStatuses() {
        for (status in listOf(5, 15, 137)) {
            assertEquals(
                LinkDropReason.BOND_LOST,
                dropReasonForGattStatus(status),
                "status $status should be BOND_LOST on drop",
            )
        }
    }

    @Test
    fun dropReasonMapsEncryptionTimeoutAndNullToConnectionLost() {
        for (status in listOf(null, 8, 22, 133, 0)) {
            assertEquals(
                LinkDropReason.CONNECTION_LOST,
                dropReasonForGattStatus(status),
                "status $status should be CONNECTION_LOST on drop",
            )
        }
    }
}
