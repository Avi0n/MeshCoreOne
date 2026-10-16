package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryHistoryEmptyIconTest {
    @Test
    fun emptyUsesUptrendChartAnalog() {
        assertEquals(AppIcons.ShowChart, TELEMETRY_HISTORY_EMPTY_ICON)
    }
}
