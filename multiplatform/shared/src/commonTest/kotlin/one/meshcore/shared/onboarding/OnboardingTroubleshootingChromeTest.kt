package one.meshcore.shared.onboarding

import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubTopPadding
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingTroubleshootingChromeTest {
    @Test
    fun groupsSitAtPreferenceHubCardDensity() {
        assertEquals(PreferenceHubCardPadding, TroubleshootingMetrics.groupInset)
    }

    @Test
    fun nestedDestinationUsesSettingsHubInsets() {
        assertEquals(PreferenceHubTopPadding, TroubleshootingMetrics.topPadding)
        assertEquals(PreferenceHubSectionGap, TroubleshootingMetrics.sectionGap)
    }
}
