package one.meshcore.shared.chat

/** iOS `IncomingMessageKind.logLabel` for a direct message. */
const val INGEST_LOG_LABEL_DIRECT = "direct"

/** iOS `IncomingMessageKind.logLabel` for a channel message. */
const val INGEST_LOG_LABEL_CHANNEL = "channel"

/** iOS `Skipping duplicate \(kind.logLabel) message`. */
fun skippingDuplicateLine(kind: String): String = "Skipping duplicate $kind message"

/** iOS `Unknown \(kind.logLabel) message textType raw=\(textTypeRaw); clamping to .plain`. */
fun unknownTextTypeLine(kind: String, textTypeRaw: Int): String =
    "Unknown $kind message textType raw=$textTypeRaw; clamping to .plain"

/** iOS `Correlated channel message to RxLogEntry: pathLength=…, pathNodes=… bytes`. */
fun correlatedChannelRxLogLine(pathLength: Int, pathNodeBytes: Int): String =
    "Correlated channel message to RxLogEntry: pathLength=$pathLength, pathNodes=$pathNodeBytes bytes"

/** iOS `No RxLogEntry found for channel …, senderTimestamp: …`. */
fun noChannelRxLogEntryLine(channelIndex: Int, senderTimestamp: Long): String =
    "No RxLogEntry found for channel $channelIndex, senderTimestamp: $senderTimestamp"
