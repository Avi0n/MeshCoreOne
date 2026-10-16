package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.MessageStatus

internal suspend fun ChatSendQueueService.drainDirectMessage(envelope: DirectMessageEnvelope) {
    runQueuedDelivery(
        onFailure = { error -> failQueuedDrain(envelope.messageID, error) },
    ) {
        deliverDirectMessage(envelope)
    }
}

internal suspend fun ChatSendQueueService.deliverDirectMessage(envelope: DirectMessageEnvelope) {
    val contact = loadedContact(envelope) ?: return
    val preserveTimestamp = bumpOrPark(envelope.messageID, ChatSendQueueService.KIND_DM)
        ?.preserveTimestamp
        ?: return
    sendDirectOrPark(envelope, contact, preserveTimestamp)
}

internal suspend fun ChatSendQueueService.loadedContact(envelope: DirectMessageEnvelope) =
    when (val read = classifyRead { store.fetchContact(envelope.contactID) }) {
        is ReadOutcome.Found -> read.value
        ReadOutcome.Missing -> {
            persistDrainState { store.deletePendingSendsForMessage(envelope.messageID) }
            persistDrainState { store.updateMessageStatus(envelope.messageID, MessageStatus.FAILED) }
            null
        }
        is ReadOutcome.Transient -> parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_DM)
    }

internal suspend fun ChatSendQueueService.sendDirectOrPark(
    envelope: DirectMessageEnvelope,
    contact: ContactDto,
    preserveTimestamp: Boolean,
) {
    runQueuedDelivery(
        onFailure = { error -> parkOrRethrowDirect(envelope, error) },
    ) {
        if (envelope.isResend) {
            messageService.resendDirectMessage(envelope.messageID, contact, preserveTimestamp)
        } else {
            messageService.sendPendingDirectMessage(envelope.messageID, contact, preserveTimestamp)
        }
        persistDrainState { store.deletePendingSendsForMessage(envelope.messageID) }
        triggers.clear()
    }
}

internal suspend fun ChatSendQueueService.parkOrRethrowDirect(
    envelope: DirectMessageEnvelope,
    error: Throwable,
): Nothing {
    if (!isTransientDirectMessageError(error)) {
        logger.info(dmDrainTerminalLine(envelope.messageID, error))
        throw error
    }
    logger.info(dmDrainTransientLine(envelope.messageID, error))
    // Remap the `.failed` write failMessageAndRethrow just made back to `.pending` so the bubble
    // does not flicker while the queue is parked; duplicate prevention is via preserveTimestamp.
    persistDrainState { store.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
    parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_DM)
}
