package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.handleBLEDisconnection

/**
 * Marks every open remote-node session disconnected and returns their ids. Runs before the container is
 * torn down — the services that know which sessions were live do not survive it.
 */
internal suspend fun ConnectionManager.captureRemoteSessionsForReauth(): Set<Uuid> =
    services?.remoteNodeService?.handleBLEDisconnection() ?: emptySet()

/** A user-initiated connect or disconnect ends the queue: only an unexpected drop replays it. */
internal suspend fun ConnectionManager.clearRemoteReauthQueue() {
    captureRemoteSessionsForReauth()
    pendingReauthSessionIds = emptySet()
}

/**
 * Re-authenticates the remote-node sessions a drop left open, after the post-connect sync so the logins
 * ride a synced link (the Swift `rebuildSession` ordering). Only the ids consumed here are cleared, so a
 * drop during re-auth keeps its own capture queued for the next attempt.
 */
internal suspend fun ConnectionManager.reauthenticatePendingRemoteSessions(
    container: ServiceContainer,
    generation: Long,
) {
    if (!wantsConnection || !ownsConnectCeremony(generation, container)) return
    val awaiting = pendingReauthSessionIds
    if (awaiting.isEmpty()) return
    container.remoteNodeService.handleBLEReconnection(awaiting)
    if (!wantsConnection || !ownsConnectCeremony(generation, container)) return
    pendingReauthSessionIds = pendingReauthSessionIds - awaiting
}
