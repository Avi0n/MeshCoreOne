package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.Color
import one.meshcore.shared.theme.AppTheme

/** Theme inputs that freeze one chat span's colour, weight, and mention background. */
internal data class ChatSpanPalette(
    val outgoing: Boolean,
    val theme: AppTheme,
    val useDark: Boolean,
    val highContrast: Boolean,
    val baseColor: Color,
)
