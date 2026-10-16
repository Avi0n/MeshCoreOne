package one.meshcore.shared.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoOpBillingClientTest {
    @Test
    fun queryReturnsDetailsForEachId() = runTest {
        val client = NoOpBillingClient()
        val details = client.queryProductDetails(listOf("a", "b"))
        assertEquals(2, details.size)
        assertEquals("a", details[0].productId)
    }

    @Test
    fun launchPurchaseErrorsAndOwnedEmpty() = runTest {
        val client = NoOpBillingClient()
        val result = client.launchPurchase("tip_1")
        assertTrue(result is PurchaseResult.Error)
        assertEquals(PurchaseErrorKind.UNSUPPORTED, result.kind)
        val owned = client.queryOwnedPurchases()
        assertTrue(owned is PurchaseQueryResult.Available && owned.purchases.isEmpty())
    }
}
