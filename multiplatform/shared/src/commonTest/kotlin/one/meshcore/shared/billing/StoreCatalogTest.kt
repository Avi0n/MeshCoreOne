package one.meshcore.shared.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreCatalogTest {
    @Test
    fun themeAndTipCountsMatchIosSixTipsNineThemes() {
        assertEquals(9, StoreCatalog.Theme.all.size)
        assertEquals(6, StoreCatalog.Tip.all.size)
        assertEquals(1 + 9 + 6, StoreCatalog.allProductIds.size)
    }

    @Test
    fun entitlementStoreGrantsThemeBundle() {
        val store = EntitlementStore()
        assertFalse(store.ownsThemeBundle())
        store.grant(StoreCatalog.THEME_BUNDLE)
        assertTrue(store.ownsThemeBundle())
        assertTrue(store.isOwned(StoreCatalog.THEME_BUNDLE))
    }
}
