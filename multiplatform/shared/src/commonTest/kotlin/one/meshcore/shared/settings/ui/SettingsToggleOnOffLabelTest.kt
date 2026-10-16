package one.meshcore.shared.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsToggleOnOffLabelTest {
    @Test
    fun checkedSpeaksOn() {
        assertEquals("On", toggleOnOffLabel(checked = true, onLabel = "On", offLabel = "Off"))
    }

    @Test
    fun uncheckedSpeaksOff() {
        assertEquals("Off", toggleOnOffLabel(checked = false, onLabel = "On", offLabel = "Off"))
    }
}
