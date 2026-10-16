package one.meshcore.shared.chat

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/**
 * A fresh multicast subscription to outbound-message lifecycle events. Every subscriber receives
 * every event yielded after this call; consumers re-subscribe per connection. Mirrors
 * `statusEvents()`.
 */
suspend fun MessageService.statusEvents(): ReceiveChannel<MessageStatusEvent> = statusBroadcaster.subscribe()

/**
 * Cold flow of [statusEvents]. [messageService] is read when collection starts, so a process-lifetime
 * host binds the service from the current connection. No service completes without emitting. The
 * subscription is cancelled when the collector stops.
 */
fun liveMessageStatusEvents(messageService: suspend () -> MessageService?): Flow<MessageStatusEvent> = flow {
    val channel = messageService()?.statusEvents() ?: return@flow
    channel.consumeEach { emit(it) }
}

/** Ends every subscriber's stream so consumer tasks release the service. Mirrors `finishStatusEvents`. */
suspend fun MessageService.finishStatusEvents() = statusBroadcaster.finish()

/**
 * Broadcasts `.failed` for a message from outside the send paths. The send queue's terminal catch uses
 * this; inline send paths yield the event directly. Mirrors `notifyMessageFailed`.
 */
suspend fun MessageService.notifyMessageFailed(messageID: Uuid) =
    statusBroadcaster.yield(MessageStatusEvent.Failed(messageID))

/**
 * Broadcasts [MessageStatusEvent.HeardRepeatRecorded] after [one.meshcore.shared.chat.pathdetail.HeardRepeatsService]
 * correlates an RX-log echo. Open threads reload the message for the footer count.
 */
suspend fun MessageService.notifyHeardRepeatRecorded(messageID: Uuid, count: Int) =
    statusBroadcaster.yield(MessageStatusEvent.HeardRepeatRecorded(messageID, count))

/**
 * Broadcasts [MessageStatusEvent.MessagesRegionUpdated] after a late RX-log attribution rewrote
 * region or hop-path fields on stored messages. Open threads reload those rows.
 */
suspend fun MessageService.notifyMessagesRegionUpdated(messageIDs: List<Uuid>) {
    if (messageIDs.isEmpty()) return
    statusBroadcaster.yield(MessageStatusEvent.MessagesRegionUpdated(messageIDs))
}

/**
 * Starts the ACK listener, routing each [MeshEvent.Acknowledgement] on the session stream through
 * [MessageService.handleAcknowledgement]. Without it, pending DMs stay `.sent` even when ACKs arrive.
 * Counterpart: [MessageService.stopEventMonitoring]. Does not start the expiry checker. Mirrors
 * `startEventMonitoring`.
 *
 * [MessageService.pendingAcks] stays empty until this connection's own sends fill it: a stored `.sent`
 * DM is never swept back into give-up tracking. Its live entry carried a per-attempt send time and the
 * firmware's suggested timeout, neither of which survives in the row, so any reconstruction would fail
 * messages on a deadline the app can no longer influence. A `.sent` DM whose tracking died with the
 * previous connection stays `.sent`, and a late ACK still resolves it through
 * [MessageService.handleAcknowledgement].
 */
fun MessageService.startEventMonitoring() {
    eventListenerJob?.cancel()
    eventListenerJob = scope.launch {
        session.events.collect { event ->
            if (event is MeshEvent.Acknowledgement) {
                // A status-write fault, including an Error, must leave later acknowledgements collecting.
                runReportingThrowable<Unit>(
                    onFailure = { error ->
                        logger.error(
                            "Failed to write delivered status: ${error.message ?: error.toString()}",
                        )
                    },
                ) {
                    handleAcknowledgement(event.code, event.tripTime?.toLong())
                }
            }
        }
    }
}

/** Cancels the ACK listener only; leaves the expiry checker and in-flight DMs untouched. */
fun MessageService.stopEventMonitoring() {
    eventListenerJob?.cancel()
    eventListenerJob = null
}
