package one.meshcore.shared

import one.meshcore.shared.chat.ui.JvmTimestampFormatter
import one.meshcore.shared.chat.ui.TimestampFormatter
import java.time.ZoneId
import java.util.Locale

internal actual val timestampTestFormatter: TimestampFormatter =
    JvmTimestampFormatter(Locale.UK, ZoneId.of("UTC"), "Unavailable")
