package one.meshcore.shared.chat.ui

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal fun chatClockLabel(epochSeconds: Double, formatter: TimestampFormatter): String =
    epochSeconds.toTimestampEpochSecondOrNull()?.let(formatter::clock) ?: formatter.unavailable

internal fun chatClockLabel(epochSeconds: Long, formatter: TimestampFormatter): String =
    formatter.clock(epochSeconds) ?: formatter.unavailable

@OptIn(ExperimentalTime::class)
internal fun chatDayLabel(
    epochSeconds: Double,
    formatter: TimestampFormatter,
    nowSeconds: Long = Clock.System.now().epochSeconds,
): ChatDayLabel {
    val seconds = epochSeconds.toTimestampEpochSecondOrNull()
    val day = seconds?.let(formatter::localEpochDay)
    val absolute = seconds?.let(formatter::date)
    val today = formatter.localEpochDay(nowSeconds)
    val kind = when {
        day == null || today == null || absolute == null -> ChatDayLabel.Kind.OTHER
        day == today -> ChatDayLabel.Kind.TODAY
        day == today - PREVIOUS_CALENDAR_DAY -> ChatDayLabel.Kind.YESTERDAY
        else -> ChatDayLabel.Kind.OTHER
    }
    return ChatDayLabel(kind, absolute?.takeIf { day != null } ?: formatter.unavailable)
}

data class ChatDayLabel(val kind: Kind, val absolute: String) {
    enum class Kind { TODAY, YESTERDAY, OTHER }
}

sealed class ConversationListTime {
    data class Clock(val text: String) : ConversationListTime()
    data object Yesterday : ConversationListTime()
    data class MonthDay(val text: String) : ConversationListTime()
}

internal fun conversationListTime(
    epochSeconds: Double,
    nowSeconds: Long,
    formatter: TimestampFormatter,
): ConversationListTime {
    val seconds = epochSeconds.toTimestampEpochSecondOrNull()
        ?: return ConversationListTime.MonthDay(formatter.unavailable)
    return conversationListTime(seconds, nowSeconds, formatter)
}

internal fun conversationListTime(
    epochSeconds: Long,
    nowSeconds: Long,
    formatter: TimestampFormatter,
): ConversationListTime {
    val day = formatter.localEpochDay(epochSeconds)
    val today = formatter.localEpochDay(nowSeconds)
    return when {
        day == null -> ConversationListTime.MonthDay(formatter.unavailable)
        today == null -> ConversationListTime.MonthDay(chatMonthDayLabel(epochSeconds, formatter))
        day == today -> formatter.clock(epochSeconds)?.let { ConversationListTime.Clock(it) }
            ?: ConversationListTime.MonthDay(formatter.unavailable)
        day == today - PREVIOUS_CALENDAR_DAY -> ConversationListTime.Yesterday
        else -> ConversationListTime.MonthDay(chatMonthDayLabel(epochSeconds, formatter))
    }
}

internal fun chatMonthDayLabel(epochSeconds: Long, formatter: TimestampFormatter): String =
    formatter.monthDay(epochSeconds) ?: formatter.unavailable

internal fun chatMonthDayLabel(epochSeconds: Double, formatter: TimestampFormatter): String =
    epochSeconds.toTimestampEpochSecondOrNull()?.let(formatter::monthDay) ?: formatter.unavailable

internal fun chartLocalEpochDay(epochSeconds: Long, formatter: TimestampFormatter): Long? =
    formatter.localEpochDay(epochSeconds)

internal fun chartLocalEpochDay(epochSeconds: Double, formatter: TimestampFormatter): Long? =
    epochSeconds.toTimestampEpochSecondOrNull()?.let(formatter::localEpochDay)

internal fun chatMonthDayClockLabel(epochSeconds: Long, formatter: TimestampFormatter): String =
    formatter.monthDayClock(epochSeconds) ?: formatter.unavailable

private const val PREVIOUS_CALENDAR_DAY = 1L
