package one.meshcore.shared.chat

/**
 * Firmware device-error codes the send-retry classifier treats as transient. These are MC1's
 * retry-policy interpretations of a radio `deviceError(code)`; the transient-vs-terminal taxonomy is a
 * send-queue policy, not a protocol constant. Mirrors the Swift `FirmwareDeviceErrorCode`.
 */
object FirmwareDeviceErrorCode {
    /** `TABLE_FULL` on the direct-message path: the outbound DM pool is briefly exhausted. */
    const val DIRECT_MESSAGE_TABLE_FULL: Int = 3

    /**
     * `NOT_FOUND` on the channel broadcast path. Firmware emits it for two failures sharing one wire
     * code: transient pool exhaustion (park and retry) and a terminal stale channel index (the user
     * deleted the channel between enqueue and drain). The send queue disambiguates them.
     */
    const val CHANNEL_MESSAGE_NOT_FOUND: Int = 2
}
