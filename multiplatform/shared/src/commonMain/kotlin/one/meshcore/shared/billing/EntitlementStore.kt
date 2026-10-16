package one.meshcore.shared.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory entitlement flags. Ownership changes are exposed as [ownedProducts] so theme application
 * can revert a paid selection after refund without a full restart, and [isAuthoritative] says whether
 * that set is yet Play's answer — an empty set before it is "not known", not "owns nothing".
 *
 * Every entitlement has one provenance: a verified Play purchase recorded through [grant], which is
 * also the reconciler's revocation candidate set ([purchasedProducts]). Nothing else may grant, so no
 * unpaid path can unlock paid content, mirroring iOS where only StoreKit entitlements unlock a theme.
 */
class EntitlementStore {
    private val _ownedProducts = MutableStateFlow<Set<String>>(emptySet())
    private val _isAuthoritative = MutableStateFlow(false)
    private val _pendingPurchase = MutableStateFlow<PendingPurchase?>(null)

    /** Product ids currently granted: the theme bundle, individual themes, tips. */
    val ownedProducts: StateFlow<Set<String>> = _ownedProducts.asStateFlow()

    /** The same set as a snapshot, for callers outside a composition. Every id came from Play. */
    val purchasedProducts: Set<String> get() = _ownedProducts.value

    /**
     * Whether [ownedProducts] is Play's answer rather than a not-yet-known set. False until a query
     * comes back [PurchaseQueryResult.Available]; an empty owned set before that means "still asking",
     * so nothing may treat it as "owns nothing". Mirrors iOS `StoreService.loadState == .loaded`, the
     * gate that keeps a cold start or an offline launch from reverting a paid theme selection.
     * One-way: a later outage does not un-know an answer Play already gave.
     */
    val isAuthoritative: StateFlow<Boolean> = _isAuthoritative.asStateFlow()

    /**
     * Ask-to-Buy / slow-payment banner. App-lifetime, matching iOS `StoreState.pendingPurchase`, so
     * leaving Support and coming back still shows "Awaiting approval" until Play delivers PURCHASED.
     */
    val pendingPurchase: StateFlow<PendingPurchase?> = _pendingPurchase.asStateFlow()

    fun isOwned(productId: String): Boolean {
        val owned = _ownedProducts.value
        return productId in owned || (productId in StoreCatalog.Theme.all && StoreCatalog.THEME_BUNDLE in owned)
    }

    /** Grant from a verified Play purchase; revocable when the purchase is refunded. */
    fun grant(productId: String) {
        _ownedProducts.value = _ownedProducts.value + productId
    }

    fun revoke(productId: String) {
        _ownedProducts.value = _ownedProducts.value - productId
    }

    fun ownsThemeBundle(): Boolean = StoreCatalog.THEME_BUNDLE in _ownedProducts.value

    fun clear() {
        _ownedProducts.value = emptySet()
        _pendingPurchase.value = null
    }

    /** Records or clears the Ask-to-Buy banner from a Support-screen purchase outcome. */
    fun applyPurchaseResult(result: PurchaseResult, displayName: String) {
        _pendingPurchase.value = pendingPurchaseAfterResult(_pendingPurchase.value, result, displayName)
    }

    /**
     * Drops the banner once the pending product is entitled. Called when [ownedProducts] changes
     * and when Support reappears (iOS `reconcilePendingPurchase`).
     */
    fun reconcilePendingPurchase() {
        val pending = _pendingPurchase.value ?: return
        if (isPendingPurchaseResolved(pending, _ownedProducts.value)) {
            _pendingPurchase.value = null
        }
    }

    /**
     * Clears a matching pending banner. Used when a consumable tip is consumed (it never stays in
     * [ownedProducts]) and when the same product is purchased outright.
     */
    fun clearPendingIfProduct(productId: String) {
        if (_pendingPurchase.value?.productId == productId) {
            _pendingPurchase.value = null
        }
    }

    /**
     * Records that Play answered with a full owned set. Only [EntitlementReconciler] may call it, and
     * only for a [PurchaseQueryResult.Available] result: an [PurchaseQueryResult.Unavailable] query
     * proves nothing about ownership.
     */
    internal fun markAuthoritative() {
        _isAuthoritative.value = true
    }
}
