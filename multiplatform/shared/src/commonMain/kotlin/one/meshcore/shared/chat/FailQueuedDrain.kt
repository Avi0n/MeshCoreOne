package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** The only `.failed` broadcast from the send queue. A non-cancellation escape is terminal for the envelope. */
internal suspend fun ChatSendQueueService.failQueuedDrain(messageID: Uuid, error: Throwable): Nothing {
    messageService.notifyMessageFailed(messageID)
    throw error
}
