package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the platform accessibility "high / increased contrast" preference is on.
 * Avatars raise the WCAG contrast floor (AA → AAA) so identity colors stay legible under
 * outdoor glare and system high-contrast modes.
 */
@Composable
expect fun rememberHighContrast(): Boolean

/**
 * Composition-local high-contrast flag for the active tree. Default false when the host
 * has not provided a value (previews / desktop without system mapping).
 */
val LocalHighContrast = compositionLocalOf { false }
