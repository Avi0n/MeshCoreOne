package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/**
 * Writes `.sent` after the radio has accepted a direct send. Cancellation keeps the thrown instance.
 * Any other failure is logged and left for the ACK or expiry checker.
 */
internal suspend fun MessageService.applyDirectPostSendBookkeeping(messageID: Uuid, contactID: Uuid, ackCode: Long) {
    runReportingThrowable<Unit>(
        onFailure = { error ->
            logger.warning(dmPostSendBookkeepingFailLine(messageID, error.message ?: error.toString()))
        },
    ) {
        store.updateMessageAck(messageID, ackCode, MessageStatus.SENT)
        store.updateContactLastMessage(contactID, now())
        statusBroadcaster.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT))
    }
}
