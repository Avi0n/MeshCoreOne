package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Settles ordinary failures as failed. Typed queue retirement preserves durable intent and only
 * rolls an actual retry back to sent; settlement cannot mask the original cancellation.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun MessageService.failMessageAndRethrow(error: Throwable, messageID: Uuid): Nothing {
    try {
        withContext(NonCancellable) {
            stateMutex.withLock { pendingAcks.remove(messageID) }
            if (error.isSendQueueRetirement()) {
                store.clearRetryingToSent(messageID, onlyRetrying = true)
            } else {
                store.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)
            }
        }
    } catch (cancelled: CancellationException) {
        preserveSendCancellation(error, cancelled)
    } catch (failure: Exception) {
        preserveSendCancellation(error, failure)
    }
    throw if (error is ChatDeviceException) MessageServiceException.SessionError(error) else error
}

private fun MessageService.preserveSendCancellation(error: Throwable, failure: Exception) {
    if (error !is CancellationException) throw failure
    if (failure !is CancellationException) {
        logger.warning("Cancelled send settlement failed: ${failure::class.simpleName}")
    }
    if (error !== failure) error.addSuppressed(failure)
}

// Drop the whole speculative entry on send failure so no empty-codes entry survives.
internal suspend fun MessageService.dropSpeculativeAck(messageID: Uuid, error: Throwable): Nothing {
    stateMutex.withLock { pendingAcks.remove(messageID) }
    throw error
}

internal suspend fun MessageService.mergeOrRestampAck(restamp: AckRestamp) {
    if (!restamp.sentInfo.expectedAck.contentEquals(restamp.predictedAck)) {
        logger.warning(
            expectedAckMismatchLine(
                restamp.messageID,
                restamp.attempt,
                restamp.predictedAck,
                restamp.sentInfo.expectedAck,
            ),
        )
        trackPendingAck(
            restamp.messageID,
            restamp.contactID,
            restamp.sentInfo.expectedAck,
            restamp.ackTimeoutSeconds,
        )
    } else {
        stateMutex.withLock {
            pendingAcks[restamp.messageID]?.let {
                pendingAcks[restamp.messageID] = it.copy(
                    timeoutSeconds = restamp.ackTimeoutSeconds,
                    sentAtEpochSeconds = now(),
                )
            }
        }
    }
}

/**
 * Terminal reconciliation after the retry loop returns. Peeks the pending entry: gone or delivered means
 * an owner already holds the terminal write. Present with [DirectSendSettlement.sentInfo] means the
 * in-loop wait won (take ownership, write `.delivered`). Present without means the budget is spent (roll
 * `.retrying` back to `.sent` and let the expiry checker own the give-up). Mirrors `finalizeSend`.
 */
internal suspend fun MessageService.finalizeSend(settlement: DirectSendSettlement): MessageDto {
    val tracking = stateMutex.withLock { pendingAcks[settlement.messageID] }
    val sentInfo = settlement.sentInfo

    when {
        tracking == null || tracking.isDelivered -> stateMutex.withLock { pendingAcks.remove(settlement.messageID) }
        sentInfo != null -> {
            stateMutex.withLock { pendingAcks.remove(settlement.messageID) }
            store.updateMessageAck(
                settlement.messageID,
                sentInfo.expectedAck.ackCodeLeUInt32(),
                MessageStatus.DELIVERED,
            )
            store.updateContactLastMessage(settlement.contactID, now())
            statusBroadcaster.yield(MessageStatusEvent.StatusResolved(settlement.messageID, MessageStatus.DELIVERED))
        }
        else -> {
            // Budget spent, no ACK: neither fail nor remove. clearRetryingToSent is terminal-safe, so a
            // row the checker already failed stays `.failed`. The checker owns the eventual give-up.
            if (store.clearRetryingToSent(settlement.messageID)) {
                statusBroadcaster.yield(MessageStatusEvent.StatusResolved(settlement.messageID, MessageStatus.SENT))
            }
        }
    }

    checkAndNotifyRoutingChange(
        settlement.publicKey,
        settlement.contactID,
        settlement.radioID,
        settlement.initialPathLength,
    )
    return store.fetchMessage(settlement.messageID)
        ?: throw MessageServiceException.SendFailed("Failed to fetch message")
}

/** Detects a direct/flood routing switch during the send and broadcasts it. Mirrors `checkAndNotifyRoutingChange`. */
@Suppress("TooGenericExceptionCaught")
private suspend fun MessageService.checkAndNotifyRoutingChange(
    publicKey: ByteArray,
    contactID: Uuid,
    radioID: Uuid,
    initialPathLength: Int,
) {
    try {
        val updated = session.getContact(publicKey)
        if (updated == null) {
            logger.info("Contact not found in device contacts after retry")
            return
        }
        if (updated.outPathLength != initialPathLength) {
            logger.info(
                "Routing changed for contact $contactID: $initialPathLength -> ${updated.outPathLength}",
            )
            store.saveContact(radioID, updated)
            val isNowFlood = updated.outPathLength == ChatConstants.FLOOD_PATH_SENTINEL
            statusBroadcaster.yield(MessageStatusEvent.RoutingChanged(contactID, isNowFlood))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.warning("Failed to check routing change: ${error.message ?: error.toString()}")
    }
}

/** The 6-byte DM destination prefix the wire address uses. */
internal fun ByteArray.dmDestination(): ByteArray = copyOf(ChatConstants.PUBLIC_KEY_PREFIX_BYTES)
