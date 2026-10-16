package one.meshcore.shared.connection

import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLinkListener

/** Drives the session's inbound hooks and routes a drop back into the manager. */
internal class ConnectionLinkAdapter(
    private val manager: ConnectionManager,
    private val deviceId: String,
    private val boundSession: MeshSession,
    private val generation: Long,
) : MeshLinkListener {
    override fun onReceived(bytes: ByteArray) = boundSession.deliverReceived(bytes)

    override fun onDisconnected(reason: LinkDropReason) {
        boundSession.reportDisconnected()
        manager.scope.launch {
            manager.handleLinkDrop(deviceId, reason, generation, boundSession)
        }
    }
}
