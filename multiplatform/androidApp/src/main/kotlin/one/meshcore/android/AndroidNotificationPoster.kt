package one.meshcore.android

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import one.meshcore.shared.notification.PlatformNotificationPoster
import one.meshcore.shared.notification.PlatformNotificationPosts
import one.meshcore.shared.notification.PlatformNotificationTray

/**
 * The Android implementation of the shared [PlatformNotificationPoster], backed by
 * [NotificationManagerCompat]. The shared [one.meshcore.shared.notification.NotificationCoordinator] has
 * already decided a post should happen and composed its content; this class only performs the OS call —
 * builds the `NotificationCompat`, attaches the quick-reply and mark-read actions, and posts on the
 * channel the level mapped to.
 *
 * Posting is permission-safe: the coordinator checks the live OS grant before each post, and the host
 * catches a permission revocation racing the final OS call. Each message posts under its own tag (the
 * message id) grouped by conversation so opening or marking a conversation read can cancel exactly its
 * notifications. The numeric app-icon badge is launcher-dependent on Android (the OS badges from the
 * active-notification count on its own), so [setBadge] does not force a launcher-specific count; the
 * coordinator retains the computed value for platform-independent state and diagnostics.
 *
 * Kind-specific posts and tray clears live on sibling types over one host.
 */
class AndroidNotificationPoster private constructor(host: AndroidNotificationHost) :
    PlatformNotificationPoster,
    PlatformNotificationPosts by AndroidNotificationPosts(host),
    PlatformNotificationTray by AndroidNotificationTray(host) {
    constructor(context: Context) : this(AndroidNotificationHost(context))
}

internal class AndroidNotificationHost(internal val context: Context) {
    internal val manager = NotificationManagerCompat.from(context)

    init {
        MessageNotificationChannels.ensureChannels(context)
    }
}
