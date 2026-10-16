package one.meshcore.shared.transport

import android.bluetooth.BluetoothGattCharacteristic
import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins the Kable GATT projection behind [List.writeCharacteristics]: the Nordic UART tx
 * `writeWithoutResponse` property, not a hand-built DTO, is what the write policy reads.
 */
@OptIn(ExperimentalUuidApi::class)
class WriteCharacteristicsGattTest {
    private class FakeService(
        override val serviceUuid: Uuid,
        override val characteristics: List<DiscoveredCharacteristic>,
    ) : DiscoveredService

    private class FakeCharacteristic(
        override val serviceUuid: Uuid,
        override val characteristicUuid: Uuid,
        override val properties: Characteristic.Properties,
    ) : DiscoveredCharacteristic {
        override val descriptors: List<DiscoveredDescriptor> = emptyList()
    }

    private fun characteristic(service: Uuid, characteristic: Uuid, properties: Int) = FakeCharacteristic(
        serviceUuid = service,
        characteristicUuid = characteristic,
        properties = propertiesOf(properties),
    )

    private fun propertiesOf(value: Int): Characteristic.Properties {
        val boxed = Class.forName("com.juul.kable.Characteristic\$Properties")
        val ctor = boxed.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(value) as Characteristic.Properties
    }

    @Test
    fun emptyTableHasNoWriteCommandCapability() {
        assertFalse(txAdvertisesWriteWithoutResponse(emptyList<DiscoveredService>().writeCharacteristics()))
    }

    @Test
    fun rxOnlyTableIsNotTheWritePath() {
        val table = listOf(
            FakeService(
                NORDIC_UART_SERVICE,
                listOf(
                    characteristic(
                        NORDIC_UART_SERVICE,
                        NORDIC_UART_RX,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    ),
                ),
            ),
        )
        val mapped = table.writeCharacteristics()
        assertFalse(hasNordicUartTxRx(mapped))
        assertFalse(txAdvertisesWriteWithoutResponse(mapped))
    }

    @Test
    fun txWithoutWriteCommandDoesNotAdvertiseIt() {
        val table = listOf(
            FakeService(
                NORDIC_UART_SERVICE,
                listOf(
                    characteristic(
                        NORDIC_UART_SERVICE,
                        NORDIC_UART_TX,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                    ),
                    characteristic(
                        NORDIC_UART_SERVICE,
                        NORDIC_UART_RX,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    ),
                ),
            ),
        )
        val mapped = table.writeCharacteristics()
        assertTrue(hasNordicUartTxRx(mapped))
        assertFalse(txAdvertisesWriteWithoutResponse(mapped))
        assertEquals(2, mapped.size)
        assertFalse(mapped.single { it.characteristicUuid == NORDIC_UART_TX }.advertisesWriteWithoutResponse)
    }

    @Test
    fun txWithWriteCommandAdvertisesIt() {
        val table = listOf(
            FakeService(
                NORDIC_UART_SERVICE,
                listOf(
                    characteristic(
                        NORDIC_UART_SERVICE,
                        NORDIC_UART_TX,
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    ),
                    characteristic(
                        NORDIC_UART_SERVICE,
                        NORDIC_UART_RX,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    ),
                ),
            ),
        )
        val mapped = table.writeCharacteristics()
        assertTrue(hasNordicUartTxRx(mapped))
        assertTrue(txAdvertisesWriteWithoutResponse(mapped))
        assertTrue(mapped.single { it.characteristicUuid == NORDIC_UART_TX }.advertisesWriteWithoutResponse)
    }
}
