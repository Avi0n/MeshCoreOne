package one.meshcore.shared.chat

import java.time.Instant
import java.time.ZoneId

// java.time is available on both JVM targets (Android minSdk 28 ≥ API 26, and the desktop JVM), so one
// jvmCommon actual serves both rather than a duplicated per-target actual. Local calendar day matches
// Swift's Calendar.current, which resolves against the system default zone.
internal actual fun isSameCalendarDay(epochSecondsA: Double, epochSecondsB: Double): Boolean {
    val zone = ZoneId.systemDefault()
    val dayA = Instant.ofEpochSecond(epochSecondsA.toLong()).atZone(zone).toLocalDate()
    val dayB = Instant.ofEpochSecond(epochSecondsB.toLong()).atZone(zone).toLocalDate()
    return dayA == dayB
}
