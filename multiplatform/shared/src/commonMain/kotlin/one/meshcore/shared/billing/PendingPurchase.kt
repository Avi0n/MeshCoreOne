package one.meshcore.shared.billing

/**
 * An in-flight Ask-to-Buy / slow-payment purchase awaiting approval. Drives the Support banner.
 * Resolution arrives later via [EntitlementReconciler] when Play delivers PURCHASED.
 */
data class PendingPurchase(val productId: String, val displayName: String)

/**
 * Whether [pending] is already entitled. The theme bundle resolves when the bundle id is owned or
 * every bundled theme is owned (iOS `StoreState.reconcilePendingPurchase`).
 */
fun isPendingPurchaseResolved(pending: PendingPurchase, ownedProducts: Set<String>): Boolean =
    if (pending.productId == StoreCatalog.THEME_BUNDLE) {
        StoreCatalog.THEME_BUNDLE in ownedProducts || ownedProducts.containsAll(StoreCatalog.Theme.all)
    } else {
        pending.productId in ownedProducts
    }

/**
 * How a purchase outcome updates the in-flight banner. A success clears only a matching product so
 * tipping Coffee cannot wipe an Ember Ask-to-Buy still in flight.
 */
fun pendingPurchaseAfterResult(
    current: PendingPurchase?,
    result: PurchaseResult,
    displayName: String,
): PendingPurchase? = when (result) {
    is PurchaseResult.Pending -> PendingPurchase(result.productId, displayName)
    is PurchaseResult.Success -> if (current?.productId == result.productId) null else current
    is PurchaseResult.Cancelled, is PurchaseResult.Error -> current
}
