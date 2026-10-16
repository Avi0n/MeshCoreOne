package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Speaks a one-shot message to the platform screen reader, the analogue of the iOS
 * `AccessibilityNotification.Announcement`. Reserve it for events that own no node on screen — a link
 * dropping, a list opening over cached rows. Anything that is a text change on a node the user is
 * already able to reach belongs in a `liveRegion` instead.
 */
fun interface AccessibilityAnnouncer {
    fun announce(message: String)
}

/**
 * The platform announcer for the current composition. Android speaks through the host view when a
 * screen reader is running; the desktop build has no screen-reader bridge and returns a no-op.
 */
@Composable
expect fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer

/**
 * Speaks [message] once on entering the composition, when [announce] is true. Screen entry has no
 * before-state for a live region to compare against, which is why an arriving screen that is already
 * showing stale data has to say so imperatively.
 */
@Composable
fun AnnounceOnAppear(announce: Boolean, message: String) {
    val announcer = rememberAccessibilityAnnouncer()
    LaunchedEffect(Unit) {
        if (announce) announcer.announce(message)
    }
}
