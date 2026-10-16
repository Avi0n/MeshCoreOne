package one.meshcore.shared.room.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.runExceptionFallback

/**
 * Loads the contact for a disconnected-room auth sheet. A persist fault lands null so the
 * sheet shows not-found instead of a spinner. iOS `RoomAuthenticationSheet` uses `try?`.
 * Cancellation still stops the load, and an Error still leaves it.
 */
suspend fun loadRoomAuthContact(
    fetchSession: suspend () -> RemoteNodeSessionDto?,
    fetchContact: suspend (RemoteNodeSessionDto) -> ContactDto?,
): ContactDto? = runExceptionFallback({ null }) {
    val session = fetchSession() ?: return@runExceptionFallback null
    fetchContact(session)
}

/**
 * Loads a connected repeater or room session for a node sheet. A persist fault lands null
 * so the sheet shows not-connected instead of a spinner. Cancellation still stops the load,
 * and an Error still leaves it.
 */
suspend fun loadConnectedSession(fetch: suspend () -> RemoteNodeSessionDto?): RemoteNodeSessionDto? =
    runExceptionFallback({ null }, fetch)
