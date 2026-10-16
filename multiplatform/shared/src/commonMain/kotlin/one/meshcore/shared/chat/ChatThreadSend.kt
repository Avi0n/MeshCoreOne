package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Optimistic send, retry, and delete for one open thread. */
internal class ChatThreadSend(private val host: ChatThreadViewModel, private val admission: ChatThreadAdmission) {
    /**
     * One retry at a time for this conversation, matching iOS `retryInFlight`. A double-tap otherwise
     * enqueues two resends before the bubble hides.
     */
    private val retryInFlight = Mutex()

    /**
     * Optimistic send: create the pending message (persisted `.pending`), show it immediately, pin to
     * the bottom only when already there, then enqueue. An enqueue failure folds the row to `.failed` in
     * place with an error. Mirrors `sendMessage`/`sendChannelMessage`.
     */
    suspend fun sendMessage(text: String) {
        Outbound().send(text)
    }

    private inner class Outbound {
        suspend fun send(text: String) {
            val ready = readySend(text.trim()) ?: return
            when (val key = host.conversationID.conversation) {
                is ChatConversationID.ConversationKey.Dm -> sendDirect(ready, key)
                is ChatConversationID.ConversationKey.Channel -> sendChannel(ready, key)
            }
        }

        private suspend fun readySend(trimmed: String): ReadySend? {
            if (trimmed.isEmpty()) return null
            val messageService = host.dependencies.messageService() ?: return null
            val sendQueue = host.dependencies.sendQueue() ?: return null
            if (!host.ownsQueue(sendQueue) || sendQueue.messageService !== messageService) {
                host.reportUnavailable()
                return null
            }
            host.sendErrorMessage = null
            return ReadySend(trimmed, messageService, sendQueue)
        }

        private suspend fun sendDirect(ready: ReadySend, key: ChatConversationID.ConversationKey.Dm) {
            val contact = host.dependencies.currentContact() ?: return
            if (contact.id != key.contactID || contact.radioID != host.conversationID.radioID) {
                host.reportUnavailable()
                return
            }
            val message = createPending { ready.messages.createPendingMessage(ready.trimmed, contact) } ?: return
            admission.admit(message)
            // Wide/split keeps the list composed beside the thread. Without this the
            // preview and sort stay on the previous last message until the user leaves.
            host.dependencies.onConversationsChanged()
            enqueue(message.id) {
                host.requireOwnedQueue(ready.queue)
                ready.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id))
            }
        }

        private suspend fun sendChannel(ready: ReadySend, key: ChatConversationID.ConversationKey.Channel) {
            val message = createPending {
                ready.messages.createPendingChannelMessage(
                    ready.trimmed,
                    key.channelIndex,
                    host.conversationID.radioID,
                )
            } ?: return
            admission.admit(message)
            enqueue(message.id) {
                host.requireOwnedQueue(ready.queue)
                ready.queue.enqueueChannel(
                    ChannelMessageEnvelope(
                        messageID = message.id,
                        channelIndex = key.channelIndex,
                        messageText = ready.trimmed,
                        messageTimestamp = message.timestamp,
                        localNodeName = host.dependencies.envInputs().currentUserName.ifEmpty { null },
                    ),
                )
            }
        }
    }

    private class ReadySend(val trimmed: String, val messages: MessageService, val queue: ChatSendQueueService)

    /**
     * Re-sends a failed message: flip it to `.pending` (user-initiated, so the guard allows it), then
     * enqueue a resend. A resend restamps the wire timestamp on its first drain so the mesh dedup ring does
     * not silently drop it. Mirrors `retryMessage`/`sendAgain`.
     *
     * A DM goes through [ChatSendQueueService.retryDM], which replaces the parked row and flips the stored
     * status to `.pending` in one transaction. Flipping only the coordinator would leave `.failed` on disk,
     * where the status guards refuse every later terminal write, so a delivered message reloads as "Failed".
     *
     * Offline store still exists while disconnected. iOS `retryMessage` / failed-channel retry return when
     * `dataStore` / `messageService` are gone; parking a PendingSend here would flip the bubble to Sending
     * with no radio to drain it. Channel Send Again still surfaces unable-to-send, matching `enqueueChannel`.
     */
    suspend fun retryMessage(messageID: Uuid, isResend: Boolean = false) {
        if (!retryInFlight.tryLock()) return
        try {
            retryMessageLocked(messageID, isResend)
        } finally {
            retryInFlight.unlock()
        }
    }

    private suspend fun retryMessageLocked(messageID: Uuid, isResend: Boolean) {
        host.sendErrorMessage = null
        val sendQueue = host.dependencies.sendQueue()
        if (sendQueue == null) {
            if (isResend) host.reportUnavailable()
            return
        }
        if (!host.ownsQueue(sendQueue)) {
            host.reportUnavailable()
            return
        }
        when (val key = host.conversationID.conversation) {
            is ChatConversationID.ConversationKey.Dm -> retryDirectLocked(messageID, isResend, sendQueue)
            is ChatConversationID.ConversationKey.Channel ->
                retryChannelLocked(messageID, isResend, sendQueue, key)
        }
    }

    private suspend fun retryDirectLocked(messageID: Uuid, isResend: Boolean, sendQueue: ChatSendQueueService) {
        val contact = host.dependencies.currentContact() ?: return
        val key = host.conversationID.conversation as? ChatConversationID.ConversationKey.Dm ?: return
        if (contact.id != key.contactID || contact.radioID != host.conversationID.radioID) {
            host.reportUnavailable()
            return
        }
        val envelope = DirectMessageEnvelope(messageID, contact.id, isResend = isResend)
        markRetryPending(messageID)
        enqueue(messageID) {
            host.requireOwnedQueue(sendQueue)
            sendQueue.retryDM(envelope)
        }
    }

    private suspend fun retryChannelLocked(
        messageID: Uuid,
        isResend: Boolean,
        sendQueue: ChatSendQueueService,
        key: ChatConversationID.ConversationKey.Channel,
    ) {
        val message = host.coordinator.messagesByID[messageID] ?: return
        val envelope = ChannelMessageEnvelope(
            messageID = messageID,
            channelIndex = key.channelIndex,
            isResend = true,
            messageText = message.text,
            messageTimestamp = message.timestamp,
            localNodeName = host.dependencies.envInputs().currentUserName.ifEmpty { null },
        )
        if (isResend) {
            // Resending preserves the original terminal status rather than replacing its queue row.
            enqueue(messageID, preserveSentStatus = message.status == MessageStatus.SENT) {
                host.requireOwnedQueue(sendQueue)
                sendQueue.enqueueChannel(envelope)
            }
        } else {
            markRetryPending(messageID)
            enqueue(messageID) {
                host.requireOwnedQueue(sendQueue)
                sendQueue.retryChannel(envelope)
            }
        }
    }

    private fun markRetryPending(messageID: Uuid) {
        host.writer?.applyStatusUpdate(messageID, MessageStatus.PENDING, userInitiated = true)
        host.publish()
    }

    /**
     * Deletes a message: requires a ready connection, removes the row from the store and coordinator, and
     * re-derives the DM's last-message date from the database so a deleted newest message still leaves older
     * messages visible in the conversation list. Mirrors `deleteMessage`.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun deleteMessage(messageID: Uuid) {
        if (!host.dependencies.isReady()) return
        val store = host.dependencies.store() ?: return
        try {
            store.deleteMessage(messageID)
            removeMessage(messageID)
            (host.conversationID.conversation as? ChatConversationID.ConversationKey.Dm)?.let {
                store.recomputeContactLastMessageDate(it.contactID)
            }
            host.dependencies.onConversationsChanged()
            host.publish()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            host.errorMessage = host.messages.userFacing(error, host.messages.deleteMessageFailed)
            host.publish()
        }
    }

    /**
     * Removes a message and rebakes remaining channel neighbors in one pass so cluster-end avatars
     * and sender names move onto the new adjacent rows. Mirrors iOS `ChatTimeline.removeMessage`.
     */
    private fun removeMessage(messageID: Uuid) {
        val messages = host.coordinator.messages
        val index = messages.indexOfFirst { it.id == messageID }
        if (index < 0) return
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        host.writer?.remove(messageID)
        host.writer?.updateRenderState { state ->
            state.removingItem(messageID).copy(totalFetchedCount = (state.totalFetchedCount - 1).coerceAtLeast(0))
        }
        if (previous?.isChannelMessage == true) host.rebuildRenderItem(previous.id)
        if (next?.isChannelMessage == true) host.rebuildRenderItem(next.id)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun createPending(block: () -> MessageDto): MessageDto? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        host.sendErrorMessage = host.messages.userFacing(error, host.messages.sendFailed)
        host.publish()
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun enqueue(messageID: Uuid, preserveSentStatus: Boolean = false, block: () -> Unit) {
        val failure = try {
            block()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        } ?: return
        // Marking the row failed reads the store, which suspends, so it runs only for an ordinary failure.
        if (!preserveSentStatus) {
            runChatThreadStatusWrite(host.scope) {
                host.dependencies.store()?.markMessageFailedUnlessDelivered(messageID)
            }
            host.writer?.applyStatusUpdate(messageID, MessageStatus.FAILED)
        }
        host.sendErrorMessage = when (failure) {
            is ChatSendQueueServiceException.NotConnected -> host.messages.unableToSend
            else -> host.messages.userFacing(failure, host.messages.sendQueuePersistFailed)
        }
        host.publish()
        // The fail is already on screen; mark seen before the list reload so the row badge
        // does not reappear on pop-back (iOS `recordLocalEnqueueFailure`).
        if (!preserveSentStatus) host.markFailedSendSeenIfCurrent(messageID)
        host.dependencies.onConversationsChanged()
    }
}

private fun ChatThreadViewModel.ownsQueue(queue: ChatSendQueueService): Boolean {
    if (queue.isRetired || queue.radioID != conversationID.radioID || !dependencies.isReady()) return false
    return dependencies.sendQueue() === queue && dependencies.messageService() === queue.messageService
}

private fun ChatThreadViewModel.requireOwnedQueue(queue: ChatSendQueueService) {
    if (!ownsQueue(queue)) throw ChatSendQueueServiceException.NotConnected()
}

private fun ChatThreadViewModel.reportUnavailable() {
    sendErrorMessage = messages.unableToSend
    publish()
}
