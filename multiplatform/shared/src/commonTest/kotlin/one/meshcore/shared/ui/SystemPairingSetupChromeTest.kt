package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemPairingSetupChromeTest {
    @Test
    fun nestedDestinationUsesSettingsHubInsets() {
        assertEquals(PreferenceHubTopPadding, SystemPairingSetupMetrics.topPadding)
        assertEquals(PreferenceHubSectionGap, SystemPairingSetupMetrics.sectionGap)
    }
}
