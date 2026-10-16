package one.meshcore.shared.tools.ui

import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.ui.SignalChromeRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ClearanceChromeTest {
    @Test
    fun clearanceHeaderUsesThemeRolesNotIosTrafficLights() {
        assertEquals(SignalChromeRole.PRIMARY, ClearanceStatus.Clear.signalChromeRole())
        assertEquals(SignalChromeRole.TERTIARY, ClearanceStatus.Marginal.signalChromeRole())
        assertEquals(SignalChromeRole.TERTIARY, ClearanceStatus.PartialObstruction.signalChromeRole())
        assertEquals(SignalChromeRole.ERROR, ClearanceStatus.Blocked.signalChromeRole())
    }
}
