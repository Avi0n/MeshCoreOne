package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.Uuid

/**
 * One radio attempt for a direct message. A failure drops the speculative ACK and rethrows the same
 * instance, so the caller can still mark the row failed.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun MessageService.sendDirectOrDropSpeculative(
    messageID: Uuid,
    destination: ByteArray,
    text: String,
    timestamp: Long,
): MessageSentInfo = try {
    withPoolBackoff(
        FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL,
        config.poolBackoff,
        log = { logger.warning(it) },
    ) {
        session.sendDirectMessage(destination, text, timestamp, 0)
    }
} catch (cancelled: CancellationException) {
    dropSpeculativeAck(messageID, cancelled)
} catch (error: Throwable) {
    dropSpeculativeAck(messageID, error)
}
