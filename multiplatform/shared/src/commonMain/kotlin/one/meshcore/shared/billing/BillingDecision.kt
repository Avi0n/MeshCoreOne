package one.meshcore.shared.billing

/** Pure Play-response gating, extracted so the decisions are unit-testable without the Play SDK. */
object BillingDecision {
    /** Purchases to push on the updates flow: none unless the update was OK and carried purchases. */
    fun deliveryFor(responseOk: Boolean, purchases: List<OwnedPurchase>): List<OwnedPurchase>? =
        if (responseOk && purchases.isNotEmpty()) purchases else null

    /** A query is authoritative only on an OK response; anything else is [PurchaseQueryResult.Unavailable]. */
    fun queryResultFor(responseOk: Boolean, purchases: List<OwnedPurchase>): PurchaseQueryResult =
        if (responseOk) PurchaseQueryResult.Available(purchases) else PurchaseQueryResult.Unavailable

    /** Product details are usable only on an OK Play response. */
    fun productQueryFor(responseOk: Boolean, products: List<ProductDetails>): ProductQueryResult =
        if (responseOk) ProductQueryResult.Available(products) else ProductQueryResult.Unavailable

    /**
     * Maps an OK-response launch to its outcome by the matched purchase's state. Only [PurchaseState.PURCHASED]
     * is a real success; an Ask-to-Buy purchase arrives [PurchaseState.PENDING] and is not entitled until it
     * later transitions to PURCHASED. No match (null) or [PurchaseState.UNSPECIFIED] is an error — the sheet
     * returned OK but produced no entitled purchase for [productId]; [noMatchMessage] describes it.
     */
    fun launchOutcomeFor(productId: String, matchedState: PurchaseState?, noMatchMessage: String): PurchaseResult =
        when (matchedState) {
            PurchaseState.PURCHASED -> PurchaseResult.Success(productId)
            PurchaseState.PENDING -> PurchaseResult.Pending(productId)
            PurchaseState.UNSPECIFIED, null ->
                PurchaseResult.Error(productId, noMatchMessage, PurchaseErrorKind.VERIFICATION_FAILED)
        }

    /**
     * Maps a Play Billing response code onto [PurchaseErrorKind], mirroring iOS
     * `StoreServiceError.from(StoreKitError)`. User-cancel is not an error and is not mapped here.
     * Codes match `BillingClient.BillingResponseCode`.
     */
    fun purchaseErrorKindForPlayCode(responseCode: Int): PurchaseErrorKind = when (responseCode) {
        PLAY_FEATURE_NOT_SUPPORTED, PLAY_BILLING_UNAVAILABLE -> PurchaseErrorKind.UNSUPPORTED
        PLAY_SERVICE_DISCONNECTED, PLAY_SERVICE_UNAVAILABLE, PLAY_NETWORK_ERROR ->
            PurchaseErrorKind.NETWORK_UNAVAILABLE
        PLAY_ITEM_UNAVAILABLE -> PurchaseErrorKind.STOREFRONT_UNAVAILABLE
        PLAY_ITEM_NOT_OWNED -> PurchaseErrorKind.NOT_ENTITLED
        else -> PurchaseErrorKind.PURCHASE_FAILED
    }

    /** Play `BillingResponseCode.FEATURE_NOT_SUPPORTED`. */
    const val PLAY_FEATURE_NOT_SUPPORTED = -2

    /** Play `BillingResponseCode.SERVICE_DISCONNECTED`. */
    const val PLAY_SERVICE_DISCONNECTED = -1

    /** Play `BillingResponseCode.SERVICE_UNAVAILABLE`. */
    const val PLAY_SERVICE_UNAVAILABLE = 2

    /** Play `BillingResponseCode.BILLING_UNAVAILABLE`. */
    const val PLAY_BILLING_UNAVAILABLE = 3

    /** Play `BillingResponseCode.ITEM_UNAVAILABLE`. */
    const val PLAY_ITEM_UNAVAILABLE = 4

    /** Play `BillingResponseCode.ITEM_NOT_OWNED`. */
    const val PLAY_ITEM_NOT_OWNED = 8

    /** Play `BillingResponseCode.NETWORK_ERROR`. */
    const val PLAY_NETWORK_ERROR = 12
}
