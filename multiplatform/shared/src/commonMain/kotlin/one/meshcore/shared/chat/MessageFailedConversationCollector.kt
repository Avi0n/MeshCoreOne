package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Reloads the conversation list when a DM or channel send fails or is resent, so the failed-send
 * badge appears without an open thread collecting status events. Mirrors iOS
 * `MessageEventDispatcher` → `refreshFailedSendIndicators`.
 */
class MessageFailedConversationCollector(
    private val statusEvents: Flow<MessageStatusEvent>,
    private val scope: CoroutineScope,
    private val onConversationsChanged: suspend () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            statusEvents.collect { event ->
                if (event.shouldRefreshFailedSendIndicators()) onConversationsChanged()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
