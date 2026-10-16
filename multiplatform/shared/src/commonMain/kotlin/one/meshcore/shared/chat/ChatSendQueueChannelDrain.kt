package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.model.MessageStatus

internal suspend fun ChatSendQueueService.drainChannelMessage(envelope: ChannelMessageEnvelope) {
    runQueuedDelivery(
        onFailure = { error -> failQueuedDrain(envelope.messageID, error) },
    ) {
        deliverChannelMessage(envelope)
    }
}

internal suspend fun ChatSendQueueService.deliverChannelMessage(envelope: ChannelMessageEnvelope) {
    val preflight = bumpOrPark(envelope.messageID, ChatSendQueueService.KIND_CHANNEL) ?: return
    sendChannelOrHandle(envelope, preflight)
}

internal suspend fun ChatSendQueueService.sendChannelOrHandle(
    envelope: ChannelMessageEnvelope,
    preflight: PreflightResult,
) {
    runQueuedDelivery(
        onFailure = { error -> handleChannelDrainError(envelope, preflight.postBumpCount, error) },
    ) {
        val indexTimestamp = if (envelope.isResend) {
            messageService.resendChannelMessage(envelope.messageID, preflight.preserveTimestamp)
        } else {
            messageService.sendPendingChannelMessage(envelope.messageID)
            envelope.messageTimestamp
        }
        envelope.localNodeName?.let { nodeName ->
            reactionIndexer?.indexMessage(
                id = envelope.messageID,
                channelIndex = envelope.channelIndex,
                senderName = nodeName,
                text = envelope.messageText,
                timestamp = indexTimestamp,
            )
        }
        persistDrainState { store.deletePendingSendsForMessage(envelope.messageID) }
        channelFetchFailures.reset(envelope.messageID)
        triggers.clear()
    }
}

/** Terminal (rethrow) or transient (park) for a channel send error, disambiguating NOT_FOUND. */
internal suspend fun ChatSendQueueService.handleChannelDrainError(
    envelope: ChannelMessageEnvelope,
    postBumpCount: Int,
    error: Throwable,
): Nothing {
    if (!isTransientChannelMessageError(error)) {
        logger.info(channelDrainTerminalLine(envelope.messageID, error))
        throw error
    }
    if (isChannelMessageNotFound(error)) {
        disambiguateChannelNotFound(envelope, postBumpCount, error)
    }
    logger.info(channelDrainTransientLine(envelope.messageID, error))
    persistDrainState { store.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
    parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_CHANNEL)
}

/**
 * NOT_FOUND from the channel send is either transient pool exhaustion (park) or a terminal deleted
 * channel (rethrow). Below [ChatSendQueueConfig.disambiguateAfterAttempts] post-bump attempts, park
 * without a round-trip; at/above, read the channel from the device: gone -> terminal, present -> park,
 * fetch fault -> increment a per-envelope counter and treat as terminal only at the cap.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ChatSendQueueService.disambiguateChannelNotFound(
    envelope: ChannelMessageEnvelope,
    postBumpCount: Int,
    error: Throwable,
): Nothing {
    if (postBumpCount < config.disambiguateAfterAttempts) {
        logger.info(channelDrainNotFoundBelowThresholdLine(envelope.messageID, postBumpCount))
        persistDrainState { store.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
        parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_CHANNEL)
    }

    val stillExists = try {
        channelResolver.channelExists(envelope.channelIndex).also {
            channelFetchFailures.reset(envelope.messageID)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fetchError: Throwable) {
        channelFetchFailed(envelope, error, fetchError)
    }

    if (!stillExists) {
        logger.warning(channelDrainNotFoundConfirmedLine(envelope.messageID))
        channelFetchFailures.reset(envelope.messageID)
        throw error
    }
    // Channel still exists per device — NOT_FOUND was pool exhaustion. Park.
    persistDrainState { store.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
    parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_CHANNEL)
}

internal suspend fun ChatSendQueueService.channelFetchFailed(
    envelope: ChannelMessageEnvelope,
    sendError: Throwable,
    fetchError: Throwable,
): Nothing {
    val failures = channelFetchFailures.increment(envelope.messageID)
    if (failures < config.maxConsecutiveFetchChannelFailures) {
        logger.info(
            channelDrainFetchChannelFailedLine(
                fetchError,
                failures,
                config.maxConsecutiveFetchChannelFailures,
            ),
        )
        persistDrainState { store.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
        parkAndCancel(envelope.messageID, ChatSendQueueService.KIND_CHANNEL)
    }
    logger.warning(channelDrainFetchChannelPersistentLine(envelope.messageID, failures))
    channelFetchFailures.reset(envelope.messageID)
    throw sendError
}
