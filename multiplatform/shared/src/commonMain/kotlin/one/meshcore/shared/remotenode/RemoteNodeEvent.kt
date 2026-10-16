package one.meshcore.shared.remotenode

import one.meshcore.shared.model.Uuid

/**
 * Remote-node session notifications broadcast by [RemoteNodeService.events]. The stream is multicast:
 * every subscriber receives every event. Mirrors the Swift `RemoteNodeEvent`, whose only case is a
 * session's connection-state change (login, logout, keep-alive failure, or BLE loss).
 */
sealed interface RemoteNodeEvent {
    data class SessionStateChanged(val sessionID: Uuid, val isConnected: Boolean) : RemoteNodeEvent
}
