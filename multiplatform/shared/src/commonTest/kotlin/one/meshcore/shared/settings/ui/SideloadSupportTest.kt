package one.meshcore.shared.settings.ui

import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.navigation.SettingsDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SideloadSupportTest {
    @Test
    fun sideloadHidesThePurchaseRowAndKeepsTheSponsorsLink() {
        assertFalse(SideloadDistribution.BILLING_ENABLED)
        assertEquals("https://github.com/sponsors/Avi0n", SideloadDistribution.SPONSORS_URL)
        assertFalse(SettingsDestination.SUPPORT in settingsHubAppDestinations())
        assertTrue(SettingsDestination.SUPPORT in settingsHubAppDestinations(billingEnabled = true))
        assertTrue(SettingsDestination.BACKUP in settingsHubAppDestinations())
    }
}
