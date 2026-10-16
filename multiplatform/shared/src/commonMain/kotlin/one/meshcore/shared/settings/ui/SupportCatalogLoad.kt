package one.meshcore.shared.settings.ui

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.ProductQueryResult
import one.meshcore.shared.billing.PurchaseQueryResult
import one.meshcore.shared.billing.PurchaseResult

/** Products returned by the store, and whether that response left the catalog unusable. */
internal data class SupportCatalogLoad(val products: Map<String, ProductDetails>, val failed: Boolean)

/**
 * An empty Play catalog and a failed query both mean the store did not load products.
 * A failed query is not an empty success: there is nothing to offer.
 */
internal fun supportCatalogLoad(result: ProductQueryResult): SupportCatalogLoad = when (result) {
    is ProductQueryResult.Available -> SupportCatalogLoad(
        products = result.products.associateBy { it.productId },
        failed = result.products.isEmpty(),
    )
    ProductQueryResult.Unavailable -> SupportCatalogLoad(products = emptyMap(), failed = true)
}

/** Entitlement state from the store, or the empty stand-in when Support has no store. */
internal fun <T> supportStateOrEmpty(state: StateFlow<T>?, fallback: StateFlow<T>): StateFlow<T> = state ?: fallback

/**
 * A verified purchase shows thanks and clears the status line. Anything else leaves thanks
 * alone and reports the status.
 */
internal fun applySupportPurchaseFollowUp(result: PurchaseResult, onThanks: () -> Unit, onStatus: () -> Unit) {
    if (shouldPresentPurchaseThanks(result)) onThanks() else onStatus()
}

/** Purchases stay off while one is in flight or the catalog failed to load. */
internal fun supportPurchaseEnabled(purchasingId: String?, catalogFailed: Boolean): Boolean =
    purchasingId == null && !catalogFailed

/** A blank store title falls back to the local name. */
internal fun supportStoreTitle(title: String?, fallback: String): String = title?.takeIf { it.isNotBlank() } ?: fallback

/** A restore already in flight does not start another. */
internal fun supportRestoreClick(isRestoring: Boolean, start: () -> Unit) {
    if (!isRestoring) start()
}

/** A live purchase list is success. A failed query is the status line. No reconciler does nothing. */
internal fun applySupportRestore(result: PurchaseQueryResult?, onSuccess: () -> Unit, onUnavailable: () -> Unit) {
    when (result) {
        is PurchaseQueryResult.Available -> onSuccess()
        PurchaseQueryResult.Unavailable -> onUnavailable()
        null -> Unit
    }
}
