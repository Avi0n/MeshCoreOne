package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * `View.announceForAccessibility` is soft-deprecated from API 36 in favour of pane titles, live regions
 * and typed content-change events, but it is still delivered and it remains the only way to speak an
 * event that owns no node on screen. Every such call in the app funnels through here so the suppression
 * stays in one file. The framework itself drops the announcement when no accessibility service is
 * running, so no extra gate is needed.
 */
@Composable
@Suppress("DEPRECATION")
actual fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer {
    val view = LocalView.current
    return remember(view) {
        AccessibilityAnnouncer { message ->
            if (message.isNotEmpty()) view.announceForAccessibility(message)
        }
    }
}
