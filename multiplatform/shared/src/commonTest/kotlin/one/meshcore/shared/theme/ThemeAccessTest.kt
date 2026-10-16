package one.meshcore.shared.theme

import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.billing.StoreCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeAccessTest {
    @Test
    fun defaultThemeIsAlwaysAccessible() {
        val store = EntitlementStore()
        assertTrue(ThemeAccess.isAccessible(AppTheme.DEFAULT_ID, store))
        assertTrue(ThemeAccess.isAccessible(AppTheme.DEFAULT_ID, null))
    }

    @Test
    fun paidThemeLockedWithoutEntitlement() {
        val store = EntitlementStore().apply { markAuthoritative() }
        assertFalse(ThemeAccess.isAccessible("fern", store, billingEnabled = true))
        assertEquals(AppTheme.DEFAULT_ID, ThemeAccess.resolveAccessibleThemeId("fern", store, billingEnabled = true))
    }

    @Test
    fun paidThemeIsKeptUntilTheOwnedSetIsAuthoritative() {
        val store = EntitlementStore()
        // The store is empty because Play has not answered, not because the theme is unowned.
        assertEquals("fern", ThemeAccess.resolveAccessibleThemeId("fern", store, billingEnabled = true))
    }

    @Test
    fun themeBundleUnlocksAllPaidThemes() {
        val store = EntitlementStore()
        store.grant(StoreCatalog.THEME_BUNDLE)
        assertTrue(ThemeAccess.isAccessible("fern", store, billingEnabled = true))
        assertEquals("fern", ThemeAccess.resolveAccessibleThemeId("fern", store, billingEnabled = true))
    }

    @Test
    fun individualThemeProductUnlocksOnlyThatTheme() {
        val store = EntitlementStore()
        store.grant(StoreCatalog.Theme.FERN)
        assertTrue(ThemeAccess.isAccessible("fern", store, billingEnabled = true))
        assertFalse(ThemeAccess.isAccessible("ember", store, billingEnabled = true))
    }

    @Test
    fun revokeBundleRevertsSelectedPaidTheme() {
        val store = EntitlementStore().apply { markAuthoritative() }
        store.grant(StoreCatalog.THEME_BUNDLE)
        assertEquals("nord", ThemeAccess.resolveAccessibleThemeId("nord", store, billingEnabled = true))
        store.revoke(StoreCatalog.THEME_BUNDLE)
        assertEquals(
            AppTheme.DEFAULT_ID,
            ThemeAccess.resolveAccessibleThemeId("nord", store, billingEnabled = true),
        )
    }

    @Test
    fun sideloadUnlocksNamedThemesWithoutAPurchase() {
        assertFalse(SideloadDistribution.BILLING_ENABLED)
        val store = EntitlementStore().apply { markAuthoritative() }
        assertTrue(ThemeAccess.isAccessible("fern", store))
        assertEquals("fern", ThemeAccess.resolveAccessibleThemeId("fern", store))
        assertEquals(listOf(SideloadDistribution.SPONSORS_URL), SideloadDistribution.donationUrls)
    }

    @Test
    fun nullEntitlementsTreatsPaidAsAccessible() {
        assertTrue(ThemeAccess.isAccessible("fern", null))
        assertEquals("fern", ThemeAccess.resolveAccessibleThemeId("fern", null))
    }
}
