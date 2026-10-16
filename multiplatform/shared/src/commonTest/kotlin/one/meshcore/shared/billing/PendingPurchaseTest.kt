package one.meshcore.shared.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ask-to-Buy banner rules, mirroring iOS `StoreState.pendingPurchase` /
 * `reconcilePendingPurchase`.
 */
class PendingPurchaseTest {
    @Test
    fun aPendingOutcomeSetsTheBanner() {
        val next = pendingPurchaseAfterResult(
            current = null,
            result = PurchaseResult.Pending(StoreCatalog.THEME_BUNDLE),
            displayName = "All themes",
        )
        assertEquals(PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes"), next)
    }

    @Test
    fun aMatchingSuccessClearsTheBanner() {
        val current = PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes")
        assertNull(
            pendingPurchaseAfterResult(
                current = current,
                result = PurchaseResult.Success(StoreCatalog.THEME_BUNDLE),
                displayName = "All themes",
            ),
        )
    }

    @Test
    fun anUnrelatedSuccessLeavesTheBanner() {
        val current = PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes")
        assertEquals(
            current,
            pendingPurchaseAfterResult(
                current = current,
                result = PurchaseResult.Success(StoreCatalog.Tip.TIP_1),
                displayName = "Coffee",
            ),
        )
    }

    @Test
    fun aCancelLeavesTheBanner() {
        val current = PendingPurchase(StoreCatalog.Tip.TIP_1, "Coffee")
        assertEquals(
            current,
            pendingPurchaseAfterResult(
                current = current,
                result = PurchaseResult.Cancelled(StoreCatalog.THEME_BUNDLE),
                displayName = "All themes",
            ),
        )
    }

    @Test
    fun theBundleResolvesWhenTheBundleIdIsOwned() {
        assertTrue(
            isPendingPurchaseResolved(
                PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes"),
                setOf(StoreCatalog.THEME_BUNDLE),
            ),
        )
    }

    @Test
    fun theBundleResolvesWhenEveryThemeIsOwned() {
        assertTrue(
            isPendingPurchaseResolved(
                PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes"),
                StoreCatalog.Theme.all.toSet(),
            ),
        )
    }

    @Test
    fun theBundleStaysPendingWhenOnlySomeThemesAreOwned() {
        assertFalse(
            isPendingPurchaseResolved(
                PendingPurchase(StoreCatalog.THEME_BUNDLE, "All themes"),
                setOf(StoreCatalog.Theme.EMBER),
            ),
        )
    }
}
