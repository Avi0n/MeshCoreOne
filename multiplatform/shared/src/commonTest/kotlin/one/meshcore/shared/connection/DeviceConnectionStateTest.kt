package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectionStateTest {
    @Test
    fun isOperationalIsSyncingOrReady() {
        assertTrue(DeviceConnectionState.SYNCING.isOperational)
        assertTrue(DeviceConnectionState.READY.isOperational)
        assertFalse(DeviceConnectionState.CONNECTED.isOperational)
        assertFalse(DeviceConnectionState.CONNECTING.isOperational)
        assertFalse(DeviceConnectionState.DISCONNECTED.isOperational)
    }

    @Test
    fun isConnectedIsConnectedSyncingOrReady() {
        assertTrue(DeviceConnectionState.CONNECTED.isConnected)
        assertTrue(DeviceConnectionState.SYNCING.isConnected)
        assertTrue(DeviceConnectionState.READY.isConnected)
        assertFalse(DeviceConnectionState.CONNECTING.isConnected)
        assertFalse(DeviceConnectionState.DISCONNECTED.isConnected)
    }

    @Test
    fun isRadioLinkedIncludesConnecting() {
        assertTrue(DeviceConnectionState.CONNECTING.isRadioLinked)
        assertTrue(DeviceConnectionState.CONNECTED.isRadioLinked)
        assertTrue(DeviceConnectionState.SYNCING.isRadioLinked)
        assertTrue(DeviceConnectionState.READY.isRadioLinked)
        assertFalse(DeviceConnectionState.DISCONNECTED.isRadioLinked)
    }

    @Test
    fun sendQueueDrainsOnlyWhenReady() {
        assertTrue(DeviceConnectionState.READY.canDrainSendQueue)
        for (state in DeviceConnectionState.entries.filter { it != DeviceConnectionState.READY }) {
            assertFalse(state.canDrainSendQueue, "$state must not drain the send queue")
        }
    }

    @Test
    fun threadEventCollectorsBindOnALiveSessionBeforeReady() {
        assertTrue(DeviceConnectionState.CONNECTED.canCollectThreadEvents)
        assertFalse(DeviceConnectionState.CONNECTED.canDrainSendQueue)
        assertTrue(DeviceConnectionState.SYNCING.canCollectThreadEvents)
        assertTrue(DeviceConnectionState.READY.canCollectThreadEvents)
        assertFalse(DeviceConnectionState.CONNECTING.canCollectThreadEvents)
        assertFalse(DeviceConnectionState.DISCONNECTED.canCollectThreadEvents)
    }

    @Test
    fun floodScopePushesOnALiveSessionBeforeTheSendQueueDrains() {
        assertTrue(DeviceConnectionState.CONNECTED.canPushChannelFloodScope)
        assertFalse(DeviceConnectionState.CONNECTED.canDrainSendQueue)
        assertTrue(DeviceConnectionState.SYNCING.canPushChannelFloodScope)
        assertFalse(DeviceConnectionState.SYNCING.canDrainSendQueue)
        assertTrue(DeviceConnectionState.READY.canPushChannelFloodScope)
        assertTrue(DeviceConnectionState.READY.canDrainSendQueue)
        assertFalse(DeviceConnectionState.CONNECTING.canPushChannelFloodScope)
        assertFalse(DeviceConnectionState.DISCONNECTED.canPushChannelFloodScope)
    }

    @Test
    fun transportStateProjectsOntoTheAppFacingRung() {
        assertEquals(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected.toDeviceConnectionState())
        assertEquals(DeviceConnectionState.CONNECTING, ConnectionState.Connecting("d").toDeviceConnectionState())
        assertEquals(DeviceConnectionState.CONNECTED, ConnectionState.Connected("d").toDeviceConnectionState())
        assertEquals(DeviceConnectionState.SYNCING, ConnectionState.Syncing("d").toDeviceConnectionState())
        assertEquals(DeviceConnectionState.READY, ConnectionState.Ready("d").toDeviceConnectionState())
    }

    @Test
    fun reconnectingReadsAsConnectingAndBondLostAsDisconnected() {
        assertEquals(
            DeviceConnectionState.CONNECTING,
            ConnectionState.Reconnecting("d", attempt = 2).toDeviceConnectionState(),
        )
        assertEquals(DeviceConnectionState.DISCONNECTED, ConnectionState.BondLost("d").toDeviceConnectionState())
    }
}
