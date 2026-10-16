package one.meshcore.shared.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The tx-characteristic capability read behind `KableBlePeripheral.advertisesWriteWithoutResponse`.
 * Mirrors the Swift `BLEStateMachine.captureWriteWithoutResponseCapability`, which reads the property off
 * the discovered characteristic and starts from `false`.
 */
@OptIn(ExperimentalUuidApi::class)
class BleWriteCapabilityTest {
    private val otherService = Uuid.parse("0000180F-0000-1000-8000-00805F9B34FB")

    @Test
    fun advertisedCapabilityIsHonored() {
        assertTrue(txAdvertisesWriteWithoutResponse(listOf(tx(advertises = true))))
    }

    @Test
    fun writeOnlyCharacteristicIsNotCapable() {
        assertFalse(txAdvertisesWriteWithoutResponse(listOf(tx(advertises = false))))
    }

    @Test
    fun undiscoveredTableIsNotCapable() {
        assertFalse(txAdvertisesWriteWithoutResponse(emptyList()))
    }

    @Test
    fun rxCharacteristicCapabilityIsNotTheWritePath() {
        val rx = DiscoveredWriteCharacteristic(
            serviceUuid = NORDIC_UART_SERVICE,
            characteristicUuid = NORDIC_UART_RX,
            advertisesWriteWithoutResponse = true,
        )
        assertFalse(txAdvertisesWriteWithoutResponse(listOf(rx)))
    }

    @Test
    fun anotherServicesCapabilityIsNotTheWritePath() {
        val foreign = DiscoveredWriteCharacteristic(
            serviceUuid = otherService,
            characteristicUuid = NORDIC_UART_TX,
            advertisesWriteWithoutResponse = true,
        )
        assertFalse(txAdvertisesWriteWithoutResponse(listOf(foreign, tx(advertises = false))))
    }

    @Test
    fun nordicUartIsReadyOnlyWhenTxAndRxAreBothPresent() {
        val rx = DiscoveredWriteCharacteristic(
            serviceUuid = NORDIC_UART_SERVICE,
            characteristicUuid = NORDIC_UART_RX,
            advertisesWriteWithoutResponse = false,
        )
        assertFalse(hasNordicUartTxRx(emptyList()))
        assertFalse(hasNordicUartTxRx(listOf(tx(advertises = false))))
        assertFalse(hasNordicUartTxRx(listOf(rx)))
        assertTrue(hasNordicUartTxRx(listOf(tx(advertises = false), rx)))
    }

    @Test
    fun missingNordicUartThrowsCharacteristicNotFound() {
        assertFailsWith<CharacteristicNotFoundException> {
            requireNordicUartTxRx(listOf(tx(advertises = false)))
        }
    }

    @Test
    fun emptyGattHoldAdoptsTxRxWhenTheyArriveBeforeTheWindow() = runTest {
        val snapshots = MutableStateFlow(emptyList<DiscoveredWriteCharacteristic>())
        val job = async {
            awaitNordicUartTxRx(snapshots, window = 1.seconds)
        }
        snapshots.value = listOf(
            tx(advertises = false),
            DiscoveredWriteCharacteristic(
                serviceUuid = NORDIC_UART_SERVICE,
                characteristicUuid = NORDIC_UART_RX,
                advertisesWriteWithoutResponse = false,
            ),
        )
        job.await()
    }

    @Test
    fun emptyGattHoldTimesOutWithoutTxRx() = runTest {
        assertFailsWith<CharacteristicNotFoundException> {
            awaitNordicUartTxRx(MutableStateFlow(emptyList()), window = 10.milliseconds)
        }
    }

    @Test
    fun anEmptyTableStaysUnprovenAndALaterTrueSticks() {
        assertFalse(rememberWriteWithoutResponse(alreadyProven = false, characteristics = emptyList()))
        assertFalse(
            rememberWriteWithoutResponse(
                alreadyProven = false,
                characteristics = listOf(tx(advertises = false)),
            ),
        )
        val proven = rememberWriteWithoutResponse(
            alreadyProven = false,
            characteristics = listOf(tx(advertises = true)),
        )
        assertTrue(proven)
        assertTrue(
            rememberWriteWithoutResponse(
                alreadyProven = true,
                characteristics = emptyList(),
            ),
        )
        assertTrue(
            rememberWriteWithoutResponse(
                alreadyProven = true,
                characteristics = listOf(tx(advertises = false)),
            ),
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun tx(advertises: Boolean) = DiscoveredWriteCharacteristic(
    serviceUuid = NORDIC_UART_SERVICE,
    characteristicUuid = NORDIC_UART_TX,
    advertisesWriteWithoutResponse = advertises,
)
