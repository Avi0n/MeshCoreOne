package one.meshcore.shared.billing

import kotlinx.coroutines.flow.Flow

/**
 * Platform purchase surface. Android implements with Play Billing Library; default no-op keeps
 * desktop and tests compiling. Fulfilment (acknowledge / consume) and entitlement grant/revoke run
 * through [EntitlementReconciler]; the client only exposes the raw Play state and the two fulfilment
 * operations Play requires for every purchase.
 */
interface BillingClient : PurchaseFulfiller {
    suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails>

    /**
     * Catalog query with a fail-closed outcome. A Play non-OK response is [ProductQueryResult.Unavailable]
     * so Support can show retry instead of empty prices. Default wraps [queryProductDetails] as available.
     */
    suspend fun queryProductCatalog(productIds: List<String>): ProductQueryResult =
        ProductQueryResult.Available(queryProductDetails(productIds))

    suspend fun launchPurchase(productId: String): PurchaseResult

    /**
     * Owned-purchase set from Play's queryPurchasesAsync — the input to
     * [EntitlementReconciler.reconcile] on startup/reconnect. A Play refund simply drops out of an
     * [PurchaseQueryResult.Available] list, which is how revocation is detected. A failed query
     * returns [PurchaseQueryResult.Unavailable] so a transient outage never looks like "nothing
     * owned" and never drives revocation.
     */
    suspend fun queryOwnedPurchases(): PurchaseQueryResult

    /**
     * Purchases delivered fresh by Play's PurchasesUpdatedListener (a completed sheet, or an
     * out-of-band Ask-to-Buy approval while the app is open). Not an authoritative full set, so
     * consumers run [EntitlementReconciler.apply], never [EntitlementReconciler.reconcile].
     */
    val purchaseUpdates: Flow<List<OwnedPurchase>>
}

/** The two Play fulfilment operations, split out so [EntitlementReconciler] is testable with a fake. */
interface PurchaseFulfiller {
    /** Play acknowledgePurchase for a non-consumable; returns true on OK. */
    suspend fun acknowledge(purchaseToken: String): Boolean

    /** Play consumePurchase for a consumable; returns true on OK. */
    suspend fun consume(purchaseToken: String): Boolean
}

/** Platform-neutral projection of a Play purchase, carrying the fields the lifecycle logic needs. */
data class OwnedPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: PurchaseState,
    val isAcknowledged: Boolean,
)

/** Mirrors Play `Purchase.PurchaseState`; only [PURCHASED] entitlements are granted and fulfilled. */
enum class PurchaseState {
    PURCHASED,
    PENDING,
    UNSPECIFIED,
}

/**
 * Outcome of a queryPurchasesAsync. [Available] is authoritative and may drive revocation;
 * [Unavailable] (any non-OK Play response) must not — a transient outage is not a refund.
 */
sealed class PurchaseQueryResult {
    data class Available(val purchases: List<OwnedPurchase>) : PurchaseQueryResult()

    data object Unavailable : PurchaseQueryResult()
}

/** Outcome of a product-details query. [Unavailable] is a failed store call, not an empty catalog. */
sealed class ProductQueryResult {
    data class Available(val products: List<ProductDetails>) : ProductQueryResult()

    data object Unavailable : ProductQueryResult()
}

data class ProductDetails(
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
)

sealed class PurchaseResult {
    data class Success(val productId: String) : PurchaseResult()

    /** Ask-to-Buy / slow payment: the sheet completed but the purchase is not yet PURCHASED. */
    data class Pending(val productId: String) : PurchaseResult()

    data class Cancelled(val productId: String) : PurchaseResult()

    /**
     * A failed launch. [message] is the billing-layer detail (Play debug text); [kind] selects the
     * iOS `StoreServiceError.userFacingMessage` string. [PurchaseErrorKind.PURCHASE_FAILED] wraps
     * [message] in the Purchase failed: template; the other kinds are complete sentences.
     */
    data class Error(
        val productId: String,
        val message: String,
        val kind: PurchaseErrorKind = PurchaseErrorKind.PURCHASE_FAILED,
    ) : PurchaseResult()
}

/** iOS `StoreServiceError` cases that Support maps to L10n. */
enum class PurchaseErrorKind {
    PRODUCT_NOT_FOUND,
    VERIFICATION_FAILED,
    NOT_ENTITLED,
    NETWORK_UNAVAILABLE,
    STOREFRONT_UNAVAILABLE,
    UNSUPPORTED,
    PURCHASE_FAILED,
}
