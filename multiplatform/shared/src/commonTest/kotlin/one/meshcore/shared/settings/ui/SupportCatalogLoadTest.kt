package one.meshcore.shared.settings.ui

import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.ProductQueryResult
import one.meshcore.shared.billing.PurchaseQueryResult
import one.meshcore.shared.billing.PurchaseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportCatalogLoadTest {
    private val themes = ProductDetails(
        productId = "themes.bundle",
        title = "All themes",
        description = "",
        formattedPrice = "$4.99",
    )

    @Test
    fun anEmptyCatalogAndAFailedQueryBothFailToLoad() {
        val loaded = supportCatalogLoad(ProductQueryResult.Available(listOf(themes)))
        assertEquals(mapOf("themes.bundle" to themes), loaded.products)
        assertFalse(loaded.failed)

        val empty = supportCatalogLoad(ProductQueryResult.Available(emptyList()))
        assertEquals(emptyMap(), empty.products)
        assertTrue(empty.failed)

        val unavailable = supportCatalogLoad(ProductQueryResult.Unavailable)
        assertEquals(emptyMap(), unavailable.products)
        assertTrue(unavailable.failed)
    }

    @Test
    fun entitlementStateUsesTheStoreOrTheEmptyStandIn() {
        val fallback = MutableStateFlow(emptySet<String>())
        val owned = MutableStateFlow(setOf("themes.bundle"))
        assertEquals(fallback, supportStateOrEmpty(null, fallback))
        assertEquals(owned, supportStateOrEmpty(owned, fallback))
    }

    @Test
    fun aVerifiedPurchaseShowsThanksAndAnythingElseReportsStatus() {
        var thanks = 0
        var status = 0
        applySupportPurchaseFollowUp(PurchaseResult.Success("themes.bundle"), { thanks += 1 }, { status += 1 })
        assertEquals(1, thanks)
        assertEquals(0, status)
        applySupportPurchaseFollowUp(PurchaseResult.Error("tip.1", "network"), { thanks += 1 }, { status += 1 })
        assertEquals(1, thanks)
        assertEquals(1, status)
        applySupportPurchaseFollowUp(PurchaseResult.Cancelled("tip.1"), { thanks += 1 }, { status += 1 })
        assertEquals(1, thanks)
        assertEquals(2, status)
    }

    @Test
    fun purchasesStayOffWhileOneIsInFlightOrTheCatalogFailed() {
        assertTrue(supportPurchaseEnabled(purchasingId = null, catalogFailed = false))
        assertFalse(supportPurchaseEnabled(purchasingId = "tip.1", catalogFailed = false))
        assertFalse(supportPurchaseEnabled(purchasingId = null, catalogFailed = true))
    }

    @Test
    fun aBlankStoreTitleFallsBackToTheLocalName() {
        assertEquals("All themes", supportStoreTitle(null, "All themes"))
        assertEquals("All themes", supportStoreTitle("  ", "All themes"))
        assertEquals("Themes", supportStoreTitle("Themes", "All themes"))
    }

    @Test
    fun aRestoreInFlightDoesNotStartAnother() {
        var started = false
        supportRestoreClick(isRestoring = true) { started = true }
        assertFalse(started)
        supportRestoreClick(isRestoring = false) { started = true }
        assertTrue(started)
    }

    @Test
    fun aLivePurchaseListSucceedsAndAFailedQueryDoesNot() {
        var success = 0
        var unavailable = 0
        applySupportRestore(PurchaseQueryResult.Available(emptyList()), { success += 1 }, { unavailable += 1 })
        assertEquals(1, success)
        assertEquals(0, unavailable)
        applySupportRestore(PurchaseQueryResult.Unavailable, { success += 1 }, { unavailable += 1 })
        assertEquals(1, success)
        assertEquals(1, unavailable)
        applySupportRestore(null, { success += 1 }, { unavailable += 1 })
        assertEquals(1, success)
        assertEquals(1, unavailable)
    }
}
