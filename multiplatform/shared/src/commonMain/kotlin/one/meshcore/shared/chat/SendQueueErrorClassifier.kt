package one.meshcore.shared.chat

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException

/**
 * Classifies a send error as transient (park the envelope and wait on a transport-open trigger) or
 * terminal (drop the row and broadcast `.failed`). The transient [deviceCode] differs between the DM and
 * channel paths — see [FirmwareDeviceErrorCode]. Mirrors the Swift `ChatSendQueueService.isTransientError`.
 *
 * A [MessageServiceException.SessionError] is unwrapped so the wrapped form produced by
 * `failMessageAndRethrow` matches; connection-class bridge failures (timeout, not-connected, lost link,
 * powered-off, session-not-started) are transient; a device error is transient only when its firmware
 * sub-code equals [deviceCode].
 */
internal fun isTransientError(error: Throwable, deviceCode: Int): Boolean = when (error) {
    is MessageServiceException.SessionError -> error.cause?.let { isTransientError(it, deviceCode) } ?: false
    is MessageServiceException.NotConnected -> true
    is MessageServiceException.SendFailed,
    is MessageServiceException.InvalidRecipient,
    is MessageServiceException.MessageTooLong,
    -> false
    is ChatDeviceException -> error.firmwareCode == deviceCode
    is MeshBridgeException -> when (error.code) {
        BridgeErrorCode.TIMEOUT,
        BridgeErrorCode.NOT_CONNECTED,
        BridgeErrorCode.CONNECTION_LOST,
        BridgeErrorCode.BLUETOOTH_POWERED_OFF,
        BridgeErrorCode.SESSION_NOT_STARTED,
        -> true
        // A device error's firmware sub-code reaches the classifier as a ChatDeviceException, not here.
        else -> false
    }
    else -> false
}

/** DM pool exhaustion (`TABLE_FULL`) is transient; every other code is terminal. */
internal fun isTransientDirectMessageError(error: Throwable): Boolean =
    isTransientError(error, FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)

/** Channel pool exhaustion / stale-index (`NOT_FOUND`) is transient; every other code is terminal. */
internal fun isTransientChannelMessageError(error: Throwable): Boolean =
    isTransientError(error, FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND)

/**
 * Whether [error] is a channel `NOT_FOUND` device error, raw or wrapped in
 * [MessageServiceException.SessionError]. The channel drain uses this to decide whether to run the
 * pool-exhaustion-vs-deleted disambiguation. Mirrors `ChatSendQueueService.isChannelMessageNotFound`.
 */
internal fun isChannelMessageNotFound(error: Throwable): Boolean = when (error) {
    is ChatDeviceException -> error.firmwareCode == FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
    is MessageServiceException.SessionError ->
        (error.cause as? ChatDeviceException)?.firmwareCode == FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
    else -> false
}
