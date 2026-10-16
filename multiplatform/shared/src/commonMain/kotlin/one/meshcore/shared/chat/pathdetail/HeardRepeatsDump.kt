package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.model.Uuid

/** iOS `HeardRepeatsService.processForRepeats` parse-fail preview length. */
const val CHANNEL_TEXT_PARSE_LOG_PREFIX = 50

/** iOS `HeardRepeatsService.processForRepeats` parse-fail dump. */
fun failedToParseChannelMessageTextLine(preview: String): String = "Failed to parse channel message text: $preview"

/** iOS `HeardRepeatsService.processForRepeats` duplicate dump. */
fun repeatAlreadyRecordedLine(entryID: Uuid): String = "Repeat already recorded for RX entry: ${entryID.value}"

/** iOS `HeardRepeatsService.processForRepeats` success dump. */
fun recordedRepeatLine(count: Int, messageID: Uuid): String = "Recorded repeat #$count for message ${messageID.value}"

/** iOS `HeardRepeatsService.refreshRepeats` start dump. */
fun refreshRepeatsCalledLine(messageID: Uuid): String = "refreshRepeats called for messageID: ${messageID.value}"

/** iOS `HeardRepeatsService.refreshRepeats` result dump. */
fun refreshRepeatsReturningLine(count: Int): String = "refreshRepeats returning $count repeats"
