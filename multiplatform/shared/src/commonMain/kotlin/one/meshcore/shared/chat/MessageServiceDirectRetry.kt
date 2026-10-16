package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private const val MILLISECONDS_PER_SECOND = 1000.0

/**
 * The app-layer retry loop: up to `config.maxAttempts` attempts (direct then flood after `floodAfter`),
 * precomputing and tracking each attempt's ACK before the send, waiting for the end-to-end ACK, and
 * returning the [MessageSentInfo] once acknowledged or null once the budget is spent. Mirrors
 * `sendDirectMessageWithRetryLoop`.
 */
internal suspend fun MessageService.sendDirectMessageWithRetryLoop(request: DirectSendRequest): MessageSentInfo? {
    var attempts = 0
    var floodAttempts = 0
    var isFloodMode = false
    while (attempts < config.maxAttempts && (!isFloodMode || floodAttempts < config.maxFloodAttempts)) {
        when (val step = attemptDirectSend(request, attempts, isFloodMode)) {
            is DirectAttempt.Sent -> return step.info
            is DirectAttempt.Retry -> {
                isFloodMode = step.floodMode
                attempts += 1
                floodAttempts += if (step.floodMode) 1 else 0
            }
        }
    }
    logger.warning("Message delivery failed after $attempts attempts")
    return null
}

private suspend fun MessageService.attemptDirectSend(
    request: DirectSendRequest,
    attempts: Int,
    isFloodMode: Boolean,
): DirectAttempt {
    if (attempts > 0) markDirectRetry(request, attempts)
    val floodMode = escalatedFloodMode(request, attempts, isFloodMode)
    val transmission = transmitDirect(request, attempts)
    val acked = acknowledgedDirect(request, transmission, attempts)
    return if (acked != null) DirectAttempt.Sent(acked) else DirectAttempt.Retry(floodMode)
}

private suspend fun MessageService.markDirectRetry(request: DirectSendRequest, attempts: Int) {
    logger.info("Retry sending message: attempt ${attempts + 1}/${config.maxAttempts}")
    store.updateMessageRetryStatus(
        request.messageID,
        MessageStatus.RETRYING,
        retryAttempt = attempts - 1,
        maxRetryAttempts = config.maxAttempts - 1,
    )
    statusBroadcaster.yield(
        MessageStatusEvent.Retrying(request.messageID, attempts - 1, config.maxAttempts - 1),
    )
}

private suspend fun MessageService.escalatedFloodMode(
    request: DirectSendRequest,
    attempts: Int,
    isFloodMode: Boolean,
): Boolean {
    if (attempts != config.floodAfter || isFloodMode) return isFloodMode
    logger.info("Resetting path to flood after $attempts failed attempts")
    return escalateToFlood(request)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun MessageService.transmitDirect(request: DirectSendRequest, attempts: Int): DirectTransmission {
    val senderPublicKey = session.currentSelfPublicKey() ?: throw MessageServiceException.NotConnected()
    val predictedAck = AckCodeBuilder.expectedAck(request.timestamp, attempts, request.text, senderPublicKey)
    // Pre-send floor must outlive one expiry tick; otherwise a BLE round-trip > 1s could let the
    // checker expire the speculative entry before the send returns.
    val preSendTimeout = max(
        request.timeoutSeconds ?: config.minTimeoutSeconds,
        ChatConstants.ACK_CHECK_INTERVAL_SECONDS,
    )
    trackPendingAck(request.messageID, request.contactID, predictedAck, preSendTimeout)
    val sentInfo = try {
        withPoolBackoff(
            FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL,
            config.poolBackoff,
            log = { logger.warning(it) },
        ) {
            session.sendDirectMessage(request.publicKey.dmDestination(), request.text, request.timestamp, attempts)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        dropSpeculativeAck(request.messageID, error)
    }
    return DirectTransmission(sentInfo, predictedAck)
}

private suspend fun MessageService.acknowledgedDirect(
    request: DirectSendRequest,
    transmission: DirectTransmission,
    attempts: Int,
): MessageSentInfo? {
    // If the listener consumed the predicted ACK during the send, skip the wait so `.delivered` sticks.
    if (consumedDuringSend(request.messageID)) return transmission.sentInfo
    if (waitForPredictedAck(request, transmission, attempts)) return transmission.sentInfo
    // The listener may have consumed the ACK between the pre-send guard and the wait subscription.
    if (consumedDuringWait(request.messageID)) return transmission.sentInfo
    return null
}

private suspend fun MessageService.consumedDuringSend(messageID: Uuid): Boolean {
    val tracked = stateMutex.withLock { pendingAcks[messageID] }
    return tracked == null || tracked.isDelivered
}

private suspend fun MessageService.waitForPredictedAck(
    request: DirectSendRequest,
    transmission: DirectTransmission,
    attempts: Int,
): Boolean {
    val sentInfo = transmission.sentInfo
    val ackTimeout = request.timeoutSeconds ?: max(
        config.minTimeoutSeconds,
        sentInfo.suggestedTimeoutMs / MILLISECONDS_PER_SECOND * ChatConstants.ACK_TIMEOUT_MULTIPLIER,
    )
    mergeOrRestampAck(
        AckRestamp(
            messageID = request.messageID,
            contactID = request.contactID,
            predictedAck = transmission.predictedAck,
            sentInfo = sentInfo,
            ackTimeoutSeconds = ackTimeout,
            attempt = attempts,
        ),
    )
    val acked = session.waitForAcknowledgement(sentInfo.expectedAck, ackTimeout.seconds)
    if (acked) logger.info("Message acknowledged on attempt ${attempts + 1}")
    return acked
}

private suspend fun MessageService.consumedDuringWait(messageID: Uuid): Boolean {
    val delivered = stateMutex.withLock { pendingAcks[messageID]?.isDelivered }
    return delivered != false
}

private sealed interface DirectAttempt {
    class Sent(val info: MessageSentInfo) : DirectAttempt
    class Retry(val floodMode: Boolean) : DirectAttempt
}

private class DirectTransmission(val sentInfo: MessageSentInfo, val predictedAck: ByteArray)

/** Resets the contact's path to flood and broadcasts the routing change; continues in flood mode on failure. */
@Suppress("TooGenericExceptionCaught")
private suspend fun MessageService.escalateToFlood(request: DirectSendRequest): Boolean {
    try {
        session.resetPath(request.publicKey)
        session.getContact(request.publicKey)?.let { store.saveContact(request.radioID, it) }
        statusBroadcaster.yield(MessageStatusEvent.RoutingChanged(request.contactID, isFlood = true))
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.warning("Failed to reset path: ${error.message ?: error.toString()}, continuing...")
        return true
    }
}
