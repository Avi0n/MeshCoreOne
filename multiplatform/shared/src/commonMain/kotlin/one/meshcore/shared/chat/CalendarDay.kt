package one.meshcore.shared.chat

/**
 * Whether two epoch-second instants fall on the same calendar day in the device's local time zone.
 * Mirrors Swift `Calendar.current.isDate(_:inSameDayAs:)`, which day-dividers key on. The actual uses
 * the platform calendar; both JVM targets (Android, desktop) share one `java.time` implementation.
 */
internal expect fun isSameCalendarDay(epochSecondsA: Double, epochSecondsB: Double): Boolean
