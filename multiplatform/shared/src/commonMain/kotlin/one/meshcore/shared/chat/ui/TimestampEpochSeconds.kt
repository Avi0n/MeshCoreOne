package one.meshcore.shared.chat.ui

internal fun Double.toTimestampEpochSecondOrNull(): Long? =
    takeIf { it.isFinite() && it >= MIN_TIMESTAMP_EPOCH_SECOND && it < MAX_TIMESTAMP_EPOCH_SECOND_EXCLUSIVE }
        ?.toLong()

private val MIN_TIMESTAMP_EPOCH_SECOND = Long.MIN_VALUE.toDouble()
private val MAX_TIMESTAMP_EPOCH_SECOND_EXCLUSIVE = Long.MAX_VALUE.toDouble()
