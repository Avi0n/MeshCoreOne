package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

internal interface TimestampFormatter {
    val unavailable: String

    fun clock(epochSeconds: Long): String?
    fun clockWithSeconds(epochSeconds: Long): String?
    fun date(epochSeconds: Long): String?
    fun monthDay(epochSeconds: Long): String?
    fun monthDayClock(epochSeconds: Long): String?
    fun localEpochDay(epochSeconds: Long): Long?
}

internal val LocalTimestampFormatter = compositionLocalOf<TimestampFormatter> {
    error("TimestampFormatting is required at the composition root")
}

@Composable
internal expect fun rememberTimestampFormatter(): TimestampFormatter

@Composable
fun TimestampFormatting(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTimestampFormatter provides rememberTimestampFormatter(), content = content)
}
