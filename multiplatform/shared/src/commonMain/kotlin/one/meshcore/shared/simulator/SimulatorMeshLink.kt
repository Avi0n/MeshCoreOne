package one.meshcore.shared.simulator

import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkListener

/**
 * No-op radio link for simulator/demo connect. Mirrors Swift `SimulatorMockTransport`:
 * send is discarded, inbound never arrives, and the session is never handshaked.
 */
internal class SimulatorMeshLink : MeshLink {
    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override fun setListener(listener: MeshLinkListener?) = Unit

    override suspend fun connect() {
        connected = true
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) = Unit

    override suspend fun disconnect() {
        connected = false
    }
}
