package one.meshcore.shared.billing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Play Billing purchase lifecycle, mirroring iOS `StoreService` semantics translated to the Play idiom:
 *
 * - Non-consumable (theme bundle): grant the entitlement, then acknowledge within Play's window
 *   (an unacknowledged purchase auto-refunds), idempotently — an already-acknowledged purchase is
 *   left alone. iOS parity: `StoreService.apply` + `Transaction.finish`.
 * - Consumable (tips): consume so the tip can be given again; no lasting ownership is granted. iOS
 *   parity: tips are `Transaction`s of a consumable that `finish` and never persist ownership.
 * - Pending (Ask-to-Buy / slow payment): never grant until it transitions to PURCHASED. iOS parity:
 *   `.pending` purchase outcome grants nothing.
 * - Refund: a refunded non-consumable drops out of queryPurchases, so [reconcile] revokes any owned
 *   non-consumable no longer reported as purchased. iOS parity: `revocationDate` rebuild from
 *   `currentEntitlements`. Revoking the theme bundle drives the active-theme fallback through
 *   [EntitlementStore.ownedProducts] (see `ThemeAccess.resolveAccessibleThemeId`).
 *
 * Grant runs before acknowledge/consume so a fulfilment failure still credits the buyer; both grant
 * and the Play operations are retry-safe, so a crash between them re-runs cleanly on the next
 * [reconcile].
 */
class EntitlementReconciler(private val entitlements: EntitlementStore, private val fulfiller: PurchaseFulfiller) {
    private val consumableProductIds: Set<String> = StoreCatalog.Tip.all.toSet()

    // Serializes a query-fulfil-revoke cycle against a concurrent fresh delivery. A single instance
    // is shared (the app container owns it and injects it into the Support screen), so one mutex
    // serializes every apply and reconcile over the one store.
    private val mutex = Mutex()

    /**
     * Fulfils a batch of freshly delivered purchases (PurchasesUpdatedListener). No revocation — a
     * listener delivery is not the authoritative full owned set. Holds the lock so it can't
     * interleave with a [reconcile] mid-cycle.
     */
    suspend fun apply(purchases: List<OwnedPurchase>) = mutex.withLock {
        purchases.forEach { fulfill(it) }
    }

    /**
     * Reconciles against a queryPurchasesAsync result. The [query] runs inside the lock so a
     * concurrent [apply] (an Ask-to-Buy approval arriving at foreground, exactly when startup
     * reconciles) can't grant between the query snapshot and the revoke and have its fresh grant
     * stripped. Only an authoritative [PurchaseQueryResult.Available] fulfils and revokes;
     * [PurchaseQueryResult.Unavailable] (a transient Play outage) is ignored entirely, so a failed
     * query never strips a paid entitlement. Returns the result used, for the caller's status.
     */
    suspend fun reconcile(query: suspend () -> PurchaseQueryResult): PurchaseQueryResult = mutex.withLock {
        val result = query()
        val purchases = when (result) {
            is PurchaseQueryResult.Available -> result.purchases
            PurchaseQueryResult.Unavailable -> return@withLock result
        }
        purchases.forEach { fulfill(it) }
        revokeRefunded(purchases)
        // Only now is the owned set Play's answer, so only now may a paid theme be reverted from it.
        entitlements.markAuthoritative()
        result
    }

    private suspend fun fulfill(purchase: OwnedPurchase) {
        if (purchase.state != PurchaseState.PURCHASED) return
        // Only non-consumables confer lasting ownership; a consumed tip must not stay "owned" or its
        // purchase affordance never returns.
        purchase.productIds.filterNot { it in consumableProductIds }.forEach { entitlements.grant(it) }
        if (purchase.productIds.any { it in consumableProductIds }) {
            fulfiller.consume(purchase.purchaseToken)
        } else if (!purchase.isAcknowledged) {
            fulfiller.acknowledge(purchase.purchaseToken)
        }
        // Tips never stay in ownedProducts, so the banner cannot wait on reconcilePendingPurchase.
        purchase.productIds.forEach { entitlements.clearPendingIfProduct(it) }
    }

    private fun revokeRefunded(purchases: List<OwnedPurchase>) {
        val purchasedIds = purchases
            .filter { it.state == PurchaseState.PURCHASED }
            .flatMap { it.productIds }
            .toSet()
        // Consumables legitimately drop out of the owned set after consumption rather than on refund,
        // so they are never revocation candidates.
        entitlements.purchasedProducts
            .filter { it !in consumableProductIds && it !in purchasedIds }
            .forEach { entitlements.revoke(it) }
    }
}
