package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The connectionMethods replace-by-transport-type merge — the two-axis reachability trap. Each incoming
 * method replaces the same transport type and leaves other types intact; a partial connect must never
 * drop the descriptor for the transport it didn't use (the bug that made macOS BLE radios invisible).
 */
class ConnectionMethodsMergeTest {
    private val ble = ConnectionMethod.Bluetooth(Uuid.normalized("A0000000-0000-0000-0000-000000000001"), null)
    private val wifiA = ConnectionMethod.WiFi("10.0.0.5", 4403, null)
    private val wifiB = ConnectionMethod.WiFi("192.168.1.9", 5000, null)

    @Test
    fun addingWiFiToEmptyKeepsOnlyWiFi() {
        assertEquals(listOf(wifiA), mergeConnectionMethods(emptyList(), listOf(wifiA)))
    }

    @Test
    fun newWiFiReplacesOldWiFiButKeepsBluetooth() {
        val merged = mergeConnectionMethods(listOf(ble, wifiA), listOf(wifiB))
        assertEquals(listOf(ble, wifiB), merged)
    }

    @Test
    fun newBluetoothReplacesOldBluetoothButKeepsWiFi() {
        val newBle = ConnectionMethod.Bluetooth(Uuid.normalized("B0000000-0000-0000-0000-000000000002"), "Radio")
        val merged = mergeConnectionMethods(listOf(ble, wifiA), listOf(newBle))
        assertEquals(listOf(wifiA, newBle), merged)
    }

    @Test
    fun connectingOverWiFiNeverDropsTheBluetoothDescriptor() {
        // A radio reachable both ways: a WiFi-only reconnect must leave BLE reachability intact.
        val merged = mergeConnectionMethods(listOf(ble), listOf(wifiA))
        assertEquals(listOf(ble, wifiA), merged)
    }

    @Test
    fun mergingBothTransportsAtOnceReplacesEachType() {
        val merged = mergeConnectionMethods(listOf(ble, wifiA), listOf(wifiB, ble))
        assertEquals(listOf(wifiB, ble), merged)
    }
}
