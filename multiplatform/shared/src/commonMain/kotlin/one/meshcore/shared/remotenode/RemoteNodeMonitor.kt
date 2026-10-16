package one.meshcore.shared.remotenode

import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.toLowercaseHex

/** Starts (or restarts) the collector that routes `loginSuccess`/`loginFailed` pushes to pending logins. */
fun RemoteNodeService.startEventMonitoring() {
    eventMonitorJob?.cancel()
    eventMonitorJob = scope.launch {
        nodeSession.events.collect { handleEvent(it) }
    }
}

private suspend fun RemoteNodeService.handleEvent(event: MeshEvent) {
    when (event) {
        is MeshEvent.LoginSuccess -> {
            val info = event.login
            logger.info(loginSuccessReceivedLine(info.publicKeyPrefix.toLowercaseHex()))
            handleLoginResult(
                LoginResult(true, info.isAdmin, info.permissions, info.publicKeyPrefix, info.serverTime),
                info.publicKeyPrefix,
            )
        }

        is MeshEvent.LoginFailed -> {
            val prefix = event.publicKeyPrefix ?: return
            handleLoginResult(LoginResult(false, false, null, prefix), prefix)
        }

        else -> Unit
    }
}
