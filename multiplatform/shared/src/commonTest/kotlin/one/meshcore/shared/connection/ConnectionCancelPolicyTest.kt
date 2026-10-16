package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionCancelPolicyTest {
    @Test
    fun aHalfBuiltConnectIsAbandonedWhenTheCallerCancels() {
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.Connecting("device")))
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.Disconnected))
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.Reconnecting("device", attempt = 1)))
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.BondLost("device")))
    }

    @Test
    fun aReadySessionIsNotTornDownWhenTheCallerCancels() {
        assertFalse(shouldAbandonCancelledConnect(ConnectionState.Ready("device")))
    }

    @Test
    fun aLinkThatIsUpButNotReadyIsStillAbandoned() {
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.Connected("device")))
        assertTrue(shouldAbandonCancelledConnect(ConnectionState.Syncing("device")))
    }
}
