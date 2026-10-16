package one.meshcore.shared.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.remotenode.RemoteNodeEvent

/**
 * Reloads the conversation list when a room session connects or drops, when an outbound
 * room send fails, and when a room message is ingested. iOS `notifyConversationsChanged`
 * after a saved room message, plus `handleSessionStateChange` on `sessionStateChanged` and
 * `connectionRecovered`, so unread/preview and `isConnected` stay current.
 */
class RoomSessionConversationCollector(
    private val remoteNodeEvents: Flow<RemoteNodeEvent>,
    private val roomServerEvents: Flow<RoomServerEvent>,
    private val scope: CoroutineScope,
    private val onConversationsChanged: suspend () -> Unit,
) {
    private var job: Job? = null

    /** Starts (or restarts) both collectors. Idempotent. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            launch {
                remoteNodeEvents.collect { event ->
                    if (event is RemoteNodeEvent.SessionStateChanged) onConversationsChanged()
                }
            }
            launch {
                roomServerEvents.collect { event ->
                    when (event) {
                        is RoomServerEvent.ConnectionRecovered,
                        is RoomServerEvent.MessageReceived,
                        -> onConversationsChanged()
                        is RoomServerEvent.StatusUpdated if event.status == MessageStatus.FAILED ->
                            onConversationsChanged()
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Cancels both collectors. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
