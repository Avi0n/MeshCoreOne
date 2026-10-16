package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockDriftWarningIconTest {
    @Test
    fun usesClockBadgeAnalog() {
        assertEquals(AppIcons.AccessTime, CLOCK_DRIFT_WARNING_ICON)
    }
}
