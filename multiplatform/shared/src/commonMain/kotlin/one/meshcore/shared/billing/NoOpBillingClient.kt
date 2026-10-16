package one.meshcore.shared.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** No-op billing for desktop/tests until Play Billing actual lands. */
class NoOpBillingClient : BillingClient {
    override suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails> =
        productIds.map { id -> detailsFor(id) }

    private fun detailsFor(id: String): ProductDetails =
        ProductDetails(productId = id, title = id, description = "", formattedPrice = "")

    override suspend fun launchPurchase(productId: String): PurchaseResult =
        PurchaseResult.Error(productId, MESSAGE_UNAVAILABLE, PurchaseErrorKind.UNSUPPORTED)

    override suspend fun queryOwnedPurchases(): PurchaseQueryResult = PurchaseQueryResult.Available(emptyList())

    override val purchaseUpdates: Flow<List<OwnedPurchase>> = emptyFlow()

    override suspend fun acknowledge(purchaseToken: String): Boolean = false

    override suspend fun consume(purchaseToken: String): Boolean = false

    private companion object {
        const val MESSAGE_UNAVAILABLE = "Billing not available"
    }
}
