package one.meshcore.shared.transport

import com.juul.kable.GattStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class KableConnectFailureTest {
    @Test
    fun aMissingCharacteristicStaysAMissingCharacteristic() = runTest {
        val missing = CharacteristicNotFoundException()
        var statusRead = false
        val thrown = assertFailsWith<CharacteristicNotFoundException> {
            runKableConnect(disconnectedGattStatus = {
                statusRead = true
                DEAD_BOND_STATUS
            }) {
                throw missing
            }
        }
        assertSame(missing, thrown)
        assertFalse(statusRead)
    }

    @Test
    fun aGattDeadBondBecomesBondLost() = runTest {
        val thrown = assertFailsWith<BondLostException> {
            runKableConnect(disconnectedGattStatus = { error("status should come from the exception") }) {
                throw gatt(DEAD_BOND_STATUS)
            }
        }
        assertEquals("connect failed with definitive-bond GATT status $DEAD_BOND_STATUS", thrown.message)
    }

    @Test
    fun aGattEncryptionTimeoutStaysAnEncryptionTimeout() = runTest {
        val thrown = assertFailsWith<EncryptionTimeoutException> {
            runKableConnect(disconnectedGattStatus = { null }) {
                throw gatt(ENCRYPTION_TIMEOUT_STATUS)
            }
        }
        assertEquals(
            "connect failed with encryption-timeout-class GATT status $ENCRYPTION_TIMEOUT_STATUS",
            thrown.message,
        )
    }

    @Test
    fun anUnmappedGattStatusKeepsTheOriginalException() = runTest {
        val gatt = gatt(UNMAPPED_GATT_STATUS)
        var statusRead = false
        val thrown = assertFailsWith<GattStatusException> {
            runKableConnect(disconnectedGattStatus = {
                statusRead = true
                DEAD_BOND_STATUS
            }) {
                throw gatt
            }
        }
        assertSame(gatt, thrown)
        assertFalse(statusRead)
    }

    @Test
    fun aDisconnectStatusMapsAFailureThatIsNotGatt() = runTest {
        val thrown = assertFailsWith<BondLostException> {
            runKableConnect(disconnectedGattStatus = { DEAD_BOND_STATUS }) {
                error("link dropped")
            }
        }
        assertEquals("connect failed with definitive-bond GATT status $DEAD_BOND_STATUS", thrown.message)
    }

    @Test
    fun aFailureWithoutAGattStatusKeepsTheOriginalException() = runTest {
        val failure = IllegalStateException("link dropped")
        val thrown = assertFailsWith<IllegalStateException> {
            runKableConnect(disconnectedGattStatus = { null }) { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun aCancelledConnectPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        var statusRead = false
        val thrown = assertFailsWith<CancellationException> {
            runKableConnect(disconnectedGattStatus = {
                statusRead = true
                DEAD_BOND_STATUS
            }) {
                throw cancelled
            }
        }
        assertSame(cancelled, thrown)
        assertFalse(statusRead)
    }

    @Test
    fun anErrorUsesTheDisconnectStatusWhenPresent() = runTest {
        val probe = KableConnectProbeError()
        val thrown = assertFailsWith<BondLostException> {
            runKableConnect(disconnectedGattStatus = { DEAD_BOND_STATUS }) { throw probe }
        }
        assertEquals("connect failed with definitive-bond GATT status $DEAD_BOND_STATUS", thrown.message)
    }

    @Test
    fun anErrorWithoutAGattStatusPropagates() = runTest {
        val probe = KableConnectProbeError()
        val thrown = assertFailsWith<KableConnectProbeError> {
            runKableConnect(disconnectedGattStatus = { null }) { throw probe }
        }
        assertSame(probe, thrown)
    }

    private fun gatt(status: Int) = GattStatusException(message = "gatt", cause = null, status = status)
}

private class KableConnectProbeError : Error("probe")

private const val DEAD_BOND_STATUS = 5
private const val ENCRYPTION_TIMEOUT_STATUS = 8
private const val UNMAPPED_GATT_STATUS = 19
