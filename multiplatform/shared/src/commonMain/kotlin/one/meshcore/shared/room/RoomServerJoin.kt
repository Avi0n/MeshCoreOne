package one.meshcore.shared.room

import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.createSession
import one.meshcore.shared.remotenode.isRoom
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.removeSession
import one.meshcore.shared.remotenode.startSessionKeepAlive as startRemoteNodeKeepAlive
import one.meshcore.shared.remotenode.stopSessionKeepAlive as stopRemoteNodeKeepAlive

/**
 * Joins a room: creates the session, logs in over the shared node path, stores the password only after
 * a successful login (and only when [RoomServerLogin.rememberPassword]), then best-effort syncs history.
 * Returns the updated session. [RoomServerLogin.pathLength] feeds only the login timeout (0 = direct).
 */
suspend fun RoomServerService.joinRoom(
    radioID: Uuid,
    contact: ContactDto,
    password: String?,
    login: RoomServerLogin = RoomServerLogin(),
): RemoteNodeSessionDto {
    val remoteSession = remoteNodeService.createSession(radioID, contact)
    remoteNodeService.login(remoteSession.id, password, login.pathLength, login.onTimeoutKnown)

    if (password != null && login.rememberPassword) {
        passwordStore.storePassword(contact.publicKey, password)
    }

    syncHistoryIfPossible(remoteSession.id)

    return store.fetchSession(remoteSession.id) ?: throw RemoteNodeError.SessionNotFound
}

/** Re-logs in to an existing room session (after app restart / BLE reconnect) then syncs history. */
suspend fun RoomServerService.reconnectRoom(sessionID: Uuid, pathLength: Int = 0): RemoteNodeSessionDto {
    requireRoomSession(store.fetchSession(sessionID))

    remoteNodeService.login(sessionID, password = null, pathLength = pathLength)
    syncHistoryIfPossible(sessionID)

    return store.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
}

/** Leaves a room: logs out then removes the session (clearing its stored password). */
suspend fun RoomServerService.leaveRoom(sessionID: Uuid, publicKey: ByteArray) {
    remoteNodeService.logout(sessionID)
    remoteNodeService.removeSession(sessionID, publicKey)
}

/**
 * Starts the room session's keep-alive. Each tick carries the `sync_since` watermark, so the server
 * re-sends anything missed; without it an idle room silently stops receiving.
 */
suspend fun RoomServerService.startSessionKeepAlive(sessionID: Uuid, publicKey: ByteArray) =
    remoteNodeService.startRemoteNodeKeepAlive(sessionID, publicKey)

/** Stops the room session's keep-alive (the conversation left the screen). */
suspend fun RoomServerService.stopSessionKeepAlive(sessionID: Uuid) =
    remoteNodeService.stopRemoteNodeKeepAlive(sessionID)

/**
 * Stops keep-alive on this service's connection-scoped coroutine. A screen tearing down cannot await
 * the stop on its own scope: that scope is already cancelling, which would leave the loop running.
 */
fun RoomServerService.stopSessionKeepAliveAsync(sessionID: Uuid) {
    scope.launch { remoteNodeService.stopRemoteNodeKeepAlive(sessionID) }
}

private fun requireRoomSession(session: RemoteNodeSessionDto?) {
    if (session == null) throw RemoteNodeError.SessionNotFound
    if (!session.isRoom) throw RemoteNodeError.InvalidResponse
}
