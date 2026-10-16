@file:OptIn(ExperimentalUuidApi::class)

package one.meshcore.shared.transport

import com.juul.kable.DiscoveredService
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Nordic UART Service, named from the central's perspective (matching the Swift BLEServiceUUID):
// tx = the app writes; rx = the app receives notifications.
internal val NORDIC_UART_SERVICE: Uuid = Uuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
internal val NORDIC_UART_TX: Uuid = Uuid.parse("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
internal val NORDIC_UART_RX: Uuid = Uuid.parse("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

/** One discovered GATT characteristic reduced to what the outbound-write policy reads. */
internal data class DiscoveredWriteCharacteristic(
    val serviceUuid: Uuid,
    val characteristicUuid: Uuid,
    val advertisesWriteWithoutResponse: Boolean,
)

/**
 * Whether the Nordic-UART tx characteristic among [characteristics] advertises ATT Write Commands.
 *
 * Mirrors the Swift `BLEStateMachine.captureWriteWithoutResponseCapability`, which reads the property off
 * the discovered characteristic. An empty or incomplete table — every read before discovery finishes —
 * answers `false`, the same conservative start as `txSupportsWriteWithoutResponse`, so an unproven radio
 * writes through the acknowledged path rather than firing Write Commands the radio may never receive.
 */
internal fun txAdvertisesWriteWithoutResponse(characteristics: List<DiscoveredWriteCharacteristic>): Boolean =
    characteristics.any {
        it.serviceUuid == NORDIC_UART_SERVICE &&
            it.characteristicUuid == NORDIC_UART_TX &&
            it.advertisesWriteWithoutResponse
    }

/** Whether the discovered table has both Nordic UART TX and RX, matching iOS `hasTX && hasRX`. */
internal fun hasNordicUartTxRx(characteristics: List<DiscoveredWriteCharacteristic>): Boolean {
    val nus = characteristics.filter { it.serviceUuid == NORDIC_UART_SERVICE }
    return nus.any { it.characteristicUuid == NORDIC_UART_TX } &&
        nus.any { it.characteristicUuid == NORDIC_UART_RX }
}

/**
 * iOS `handleDidDiscoverCharacteristics` throws `BLEError.characteristicNotFound` when TX or RX is
 * missing and leaves GATT up. Only a discovery timeout cancels the peripheral connection.
 */
internal fun requireNordicUartTxRx(characteristics: List<DiscoveredWriteCharacteristic>) {
    if (!hasNordicUartTxRx(characteristics)) throw CharacteristicNotFoundException()
}

/**
 * Auto-reconnect empty GATT with no error is an iOS delivery artifact: hold until TX/RX
 * appear or [window] elapses. A timeout still throws without disconnecting; the reconnect
 * loop's discovery bound is what cancels the peripheral.
 */
@Suppress("RedundantSuspendModifier")
internal suspend fun awaitNordicUartTxRx(snapshots: Flow<List<DiscoveredWriteCharacteristic>>, window: Duration) {
    val found = withTimeoutOrNull(window) {
        snapshots.first { hasNordicUartTxRx(it) }
    }
    if (found == null) throw CharacteristicNotFoundException()
}

/**
 * Once the tx characteristic advertises Write Commands, keep that answer. An empty or incomplete
 * table stays unproven so a later discovery read can still pick the capability up.
 */
internal fun rememberWriteWithoutResponse(
    alreadyProven: Boolean,
    characteristics: List<DiscoveredWriteCharacteristic>,
): Boolean = alreadyProven || txAdvertisesWriteWithoutResponse(characteristics)

/** Projects Kable's discovered GATT table onto the write policy's view of it. */
internal fun List<DiscoveredService>.writeCharacteristics(): List<DiscoveredWriteCharacteristic> = flatMap { service ->
    service.characteristics.map { characteristic ->
        DiscoveredWriteCharacteristic(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.characteristicUuid,
            advertisesWriteWithoutResponse = characteristic.properties.writeWithoutResponse,
        )
    }
}
