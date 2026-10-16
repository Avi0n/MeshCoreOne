package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSuppressionDumpTest {
    @Test
    fun suppressionLinesMatchIos() {
        assertEquals(
            "Suppressing message notifications during sync",
            SUPPRESSING_NOTIFICATIONS_DURING_SYNC,
        )
        assertEquals(
            "Suppressing message notifications during resync",
            SUPPRESSING_NOTIFICATIONS_DURING_RESYNC,
        )
        assertEquals(
            "Resuming message notifications (sync complete)",
            resumingNotificationsLine(RESUME_CONTEXT_SYNC_COMPLETE),
        )
        assertEquals(
            "Resuming message notifications (sync failed)",
            resumingNotificationsLine(RESUME_CONTEXT_SYNC_FAILED),
        )
        assertEquals(
            "Resuming message notifications (resync complete)",
            resumingNotificationsLine(RESUME_CONTEXT_RESYNC_COMPLETE),
        )
        assertEquals(
            "Resuming message notifications (resync failed)",
            resumingNotificationsLine(RESUME_CONTEXT_RESYNC_FAILED),
        )
    }
}
