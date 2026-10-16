package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionBatteryOptimizationChromeTest {
    @Test
    fun batteryRowUsesTheSystemSettingsLinkGlyph() {
        assertEquals(AppIcons.Link, CONNECTION_BATTERY_OPTIMIZATION_ICON)
    }

    @Test
    fun unsetWifiValuesShowNotSetNotAnEmDash() {
        assertEquals("Not set", connectionUnsetPreferenceText(null, "Not set"))
        assertEquals("10.0.0.1", connectionUnsetPreferenceText("10.0.0.1", "Not set"))
    }
}
