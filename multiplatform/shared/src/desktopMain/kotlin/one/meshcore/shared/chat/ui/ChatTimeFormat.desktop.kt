package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import java.util.Locale

@Composable
internal actual fun rememberTimestampFormatter(): TimestampFormatter {
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val unavailableLabel = stringResource(Res.string.android_timestamp_unavailable)
    return remember(locale, zone, unavailableLabel) { JvmTimestampFormatter(locale, zone, unavailableLabel) }
}
