package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

internal fun expectedAckMismatchLine(
    messageID: Uuid,
    attempt: Int,
    predicted: ByteArray,
    firmware: ByteArray,
): String = "expectedAck mismatch for $messageID attempt $attempt: " +
    "predicted ${predicted.toUppercaseHex()} vs firmware ${firmware.toUppercaseHex()}; " +
    "merging firmware code"

internal fun dmPostSendBookkeepingFailLine(messageID: Uuid, reason: String): String =
    "DM post-send bookkeeping failed messageID=$messageID send already accepted: $reason"

internal fun channelInlineBookkeepingFailLine(messageID: Uuid, reason: String): String =
    "Channel post-broadcast bookkeeping failed (inline path) messageID=$messageID broadcast already out: $reason"

internal fun channelPostStatusBookkeepingFailLine(messageID: Uuid, reason: String): String =
    "Channel post-status bookkeeping failed messageID=$messageID status=.sent already committed: $reason"

internal fun resendBookkeepingFailLine(messageID: Uuid, reason: String): String =
    "Resend post-status bookkeeping failed messageID=$messageID status=.sent already committed: $reason"

internal fun dmResendSendCountBookkeepingFailLine(messageID: Uuid, reason: String): String =
    "DM resend sendCount bookkeeping failed messageID=$messageID: $reason"
