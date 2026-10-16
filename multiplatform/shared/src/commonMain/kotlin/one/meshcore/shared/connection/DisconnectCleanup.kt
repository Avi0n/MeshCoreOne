package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import one.meshcore.shared.transport.BLE_DISCONNECT_STATE_LINE

internal class DisconnectCleanup(private val manager: ConnectionManager) {
    private val generation = manager.connectGeneration
    private val services = manager.services
    private val session = manager.session
    private val link = manager.link
    private val handle = manager.currentDeviceId
    private val wantsConnection = manager.wantsConnection

    @Suppress("TooGenericExceptionCaught")
    suspend fun complete(
        reason: DisconnectReason,
        captureCancelled: CancellationException?,
        onComplete: suspend () -> Unit,
    ) {
        if (captureCancelled == null) {
            completeOwned(reason, clearReauth = false, onComplete)
            return
        }
        try {
            withContext(NonCancellable) {
                completeOwned(reason, clearReauth = true, onComplete)
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== captureCancelled) captureCancelled.addSuppressed(cleanupFailure)
        }
        throw captureCancelled
    }

    private suspend fun completeOwned(reason: DisconnectReason, clearReauth: Boolean, onComplete: suspend () -> Unit) {
        if (!ownsOriginal()) return
        if (clearReauth) manager.pendingReauthSessionIds = emptySet()
        val retiredGeneration = manager.tearDownCurrent(stopSession = true)
        if (!ownsExit(retiredGeneration)) return
        if (reason.clearsUserIntent) manager.persistConnectionIntent(ConnectionIntent.UserDisconnected)
        if (!ownsExit(retiredGeneration)) return
        manager.logger.info(BLE_DISCONNECT_STATE_LINE)
        manager.mutableState.value = ConnectionState.Disconnected
        manager.logger.info(SYNC_STATE_IDLE_DISCONNECTED)
        manager.logger.info(SYNC_DISCONNECTED_RESET)
        onComplete()
    }

    private fun ownsOriginal(): Boolean = ownsExit(generation) &&
        manager.services === services &&
        manager.session === session &&
        manager.link === link

    private fun ownsExit(expectedGeneration: Long): Boolean = manager.ownsConnectAttempt(expectedGeneration) &&
        manager.currentDeviceId == handle &&
        manager.wantsConnection == wantsConnection
}
