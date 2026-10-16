package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionStateTest {
    @Test
    fun isConnectedIsFalseOnlyWhenIdleOrBondLost() {
        assertFalse(ConnectionState.Disconnected.isConnected)
        assertFalse(ConnectionState.BondLost("x").isConnected)
        assertTrue(ConnectionState.Connecting("x").isConnected)
        assertTrue(ConnectionState.Ready("x").isConnected)
        assertTrue(ConnectionState.Reconnecting("x", attempt = 1).isConnected)
    }

    @Test
    fun bleReconnectArmsFromDisconnectedAndBondLostOnly() {
        assertTrue(canArmBleReconnect(ConnectionState.Disconnected))
        assertTrue(canArmBleReconnect(ConnectionState.BondLost("x")))
        assertFalse(canArmBleReconnect(ConnectionState.Connecting("x")))
        assertFalse(canArmBleReconnect(ConnectionState.Ready("x")))
        assertFalse(canArmBleReconnect(ConnectionState.Reconnecting("x", attempt = 1)))
    }

    @Test
    fun `isBluetoothPowerOnEdge is true only on the off-to-on edge`() {
        assertTrue(isBluetoothPowerOnEdge(wasEnabled = false, isEnabled = true))
        assertFalse(isBluetoothPowerOnEdge(wasEnabled = true, isEnabled = true))
        assertFalse(isBluetoothPowerOnEdge(wasEnabled = false, isEnabled = false))
        assertFalse(isBluetoothPowerOnEdge(wasEnabled = true, isEnabled = false))
    }
}
