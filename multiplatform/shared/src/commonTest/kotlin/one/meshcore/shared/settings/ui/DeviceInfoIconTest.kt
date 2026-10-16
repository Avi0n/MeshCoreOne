package one.meshcore.shared.settings.ui

import one.meshcore.shared.navigation.SettingsDestination
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceInfoIconTest {
    @Test
    fun publicKeyPageRequiresAConnectedRadio() {
        assertTrue(SettingsDestination.PUBLIC_KEY.requiresDevice)
    }
}
