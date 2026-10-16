package one.meshcore.shared.connection

/** iOS `Suppressing message notifications during sync`. */
const val SUPPRESSING_NOTIFICATIONS_DURING_SYNC = "Suppressing message notifications during sync"

/** iOS `Suppressing message notifications during resync`. */
const val SUPPRESSING_NOTIFICATIONS_DURING_RESYNC = "Suppressing message notifications during resync"

/** iOS `drainHandlersAndResumeNotifications` context after a clean initial window. */
const val RESUME_CONTEXT_SYNC_COMPLETE = "sync complete"

/** iOS `drainHandlersAndResumeNotifications` context when the initial window throws. */
const val RESUME_CONTEXT_SYNC_FAILED = "sync failed"

/** iOS `drainHandlersAndResumeNotifications` context after a resync attempt that did not throw. */
const val RESUME_CONTEXT_RESYNC_COMPLETE = "resync complete"

/** iOS `drainHandlersAndResumeNotifications` context when a resync attempt throws. */
const val RESUME_CONTEXT_RESYNC_FAILED = "resync failed"

/** iOS `Resuming message notifications (\(context))`. */
fun resumingNotificationsLine(context: String): String = "Resuming message notifications ($context)"
