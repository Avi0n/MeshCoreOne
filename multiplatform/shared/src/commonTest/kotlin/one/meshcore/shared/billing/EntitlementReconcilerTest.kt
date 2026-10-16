package one.meshcore.shared.billing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records acknowledge/consume calls so lifecycle ordering and idempotency are assertable. */
private class FakeFulfiller : PurchaseFulfiller {
    val acknowledged = mutableListOf<String>()
    val consumed = mutableListOf<String>()

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        return true
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        consumed += purchaseToken
        return true
    }
}

/** Acknowledge fails until [succeedFromAttempt]; models a transient Play acknowledge failure. */
private class FlakyAcknowledgeFulfiller(private val succeedFromAttempt: Int) : PurchaseFulfiller {
    val acknowledged = mutableListOf<String>()

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        return acknowledged.size >= succeedFromAttempt
    }

    override suspend fun consume(purchaseToken: String): Boolean = true
}

private fun purchased(productId: String, token: String = "token-$productId", acknowledged: Boolean = false) =
    OwnedPurchase(listOf(productId), token, PurchaseState.PURCHASED, acknowledged)

private fun available(vararg purchases: OwnedPurchase) = PurchaseQueryResult.Available(purchases.toList())

class EntitlementReconcilerTest {
    @Test
    fun grantsThenAcknowledgesNonConsumable() = runTest {
        val store = EntitlementStore()
        val fulfiller = FakeFulfiller()
        EntitlementReconciler(store, fulfiller).reconcile { available(purchased(StoreCatalog.THEME_BUNDLE)) }

        assertTrue(store.ownsThemeBundle())
        assertEquals(listOf("token-${StoreCatalog.THEME_BUNDLE}"), fulfiller.acknowledged)
        assertTrue(fulfiller.consumed.isEmpty())
    }

    @Test
    fun alreadyAcknowledgedIsNotReAcknowledged() = runTest {
        val store = EntitlementStore()
        val fulfiller = FakeFulfiller()
        EntitlementReconciler(store, fulfiller)
            .reconcile { available(purchased(StoreCatalog.THEME_BUNDLE, acknowledged = true)) }

        assertTrue(store.ownsThemeBundle())
        assertTrue(fulfiller.acknowledged.isEmpty())
    }

    @Test
    fun consumableIsConsumedNotGrantedNorAcknowledged() = runTest {
        val store = EntitlementStore()
        val fulfiller = FakeFulfiller()
        EntitlementReconciler(store, fulfiller).reconcile { available(purchased(StoreCatalog.Tip.TIP_1)) }

        assertEquals(listOf("token-${StoreCatalog.Tip.TIP_1}"), fulfiller.consumed)
        assertTrue(fulfiller.acknowledged.isEmpty())
        // A consumed tip must not stay owned, or its purchase affordance never returns.
        assertFalse(store.isOwned(StoreCatalog.Tip.TIP_1))
    }

    @Test
    fun fulfillingAConsumableClearsAMatchingPendingBanner() = runTest {
        val store = EntitlementStore()
        store.applyPurchaseResult(PurchaseResult.Pending(StoreCatalog.Tip.TIP_1), "Coffee")
        EntitlementReconciler(store, FakeFulfiller()).apply(listOf(purchased(StoreCatalog.Tip.TIP_1)))
        assertEquals(null, store.pendingPurchase.value)
    }

    @Test
    fun grantingTheBundleClearsAMatchingPendingBanner() = runTest {
        val store = EntitlementStore()
        store.applyPurchaseResult(PurchaseResult.Pending(StoreCatalog.THEME_BUNDLE), "All themes")
        EntitlementReconciler(store, FakeFulfiller()).apply(listOf(purchased(StoreCatalog.THEME_BUNDLE)))
        assertEquals(null, store.pendingPurchase.value)
    }

    @Test
    fun pendingPurchaseGrantsNothing() = runTest {
        val store = EntitlementStore()
        val fulfiller = FakeFulfiller()
        val pending = OwnedPurchase(listOf(StoreCatalog.THEME_BUNDLE), "t", PurchaseState.PENDING, false)
        EntitlementReconciler(store, fulfiller).reconcile { available(pending) }

        assertFalse(store.ownsThemeBundle())
        assertTrue(fulfiller.acknowledged.isEmpty())
        assertTrue(fulfiller.consumed.isEmpty())
    }

    @Test
    fun refundRevokesBundleAndFallsBackToDefaultTheme() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        reconciler.reconcile { available(purchased(StoreCatalog.THEME_BUNDLE)) }
        val selectedTheme = StoreCatalog.Theme.EMBER.removePrefix("theme_")
        assertTrue(ThemeAccess.isAccessible(selectedTheme, store))

        // A refunded purchase simply drops out of Play's authoritative owned set.
        reconciler.reconcile { available() }

        assertFalse(store.ownsThemeBundle())
        assertEquals(
            AppTheme.DEFAULT_ID,
            ThemeAccess.resolveAccessibleThemeId(selectedTheme, store, billingEnabled = true),
        )
    }

    @Test
    fun unavailableQueryRevokesNothing() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        reconciler.reconcile { available(purchased(StoreCatalog.THEME_BUNDLE)) }
        assertTrue(store.ownsThemeBundle())

        // A transient Play outage must never strip a paid entitlement mid-session.
        reconciler.reconcile { PurchaseQueryResult.Unavailable }

        assertTrue(store.ownsThemeBundle())
    }

    @Test
    fun onlyAnAvailableQueryMakesTheOwnedSetAuthoritative() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        assertFalse(store.isAuthoritative.value)

        // A failed query answers nothing, so the empty owned set still means "not yet known".
        reconciler.reconcile { PurchaseQueryResult.Unavailable }
        assertFalse(store.isAuthoritative.value)

        reconciler.reconcile { available() }
        assertTrue(store.isAuthoritative.value)
    }

    @Test
    fun applyDoesNotRevokeOnPartialDelivery() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        store.grant(StoreCatalog.THEME_BUNDLE)

        // A listener delivery of an unrelated tip must not revoke the bundle it omits.
        reconciler.apply(listOf(purchased(StoreCatalog.Tip.TIP_1)))

        assertTrue(store.ownsThemeBundle())
    }

    @Test
    fun consumedTipIsNotRevokedWhenAbsent() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        store.grant(StoreCatalog.Tip.TIP_1)

        // Tips drop out of queryPurchases after consumption; that is not a refund.
        reconciler.reconcile { available() }

        assertTrue(store.isOwned(StoreCatalog.Tip.TIP_1))
    }

    @Test
    fun failedAcknowledgeIsRetriedAndGrantStaysIdempotent() = runTest {
        val store = EntitlementStore()
        val fulfiller = FlakyAcknowledgeFulfiller(succeedFromAttempt = 2)
        val reconciler = EntitlementReconciler(store, fulfiller)

        // Grant precedes fulfilment, so the buyer is credited even though this acknowledge fails.
        reconciler.reconcile { available(purchased(StoreCatalog.THEME_BUNDLE)) }
        assertTrue(store.ownsThemeBundle())
        assertEquals(1, fulfiller.acknowledged.size)

        // Play still reports the purchase unacknowledged; the next reconcile retries acknowledge, and
        // the re-grant is idempotent — still owned once, nothing revoked.
        reconciler.reconcile { available(purchased(StoreCatalog.THEME_BUNDLE)) }
        assertEquals(2, fulfiller.acknowledged.size)
        assertTrue(store.ownsThemeBundle())

        // Once Play reports it acknowledged, no further acknowledge is attempted.
        reconciler.reconcile { available(purchased(StoreCatalog.THEME_BUNDLE, acknowledged = true)) }
        assertEquals(2, fulfiller.acknowledged.size)
    }

    @Test
    fun applyDuringReconcileQuerySuspensionKeepsFreshGrant() = runTest {
        val store = EntitlementStore()
        val reconciler = EntitlementReconciler(store, FakeFulfiller())
        val releaseQuery = CompletableDeferred<Unit>()
        val queryStarted = CompletableDeferred<Unit>()

        // reconcile enters its locked section and suspends inside the query with a stale (empty) set.
        val reconcileJob = launch {
            reconciler.reconcile {
                queryStarted.complete(Unit)
                releaseQuery.await()
                available()
            }
        }
        queryStarted.await()

        // An Ask-to-Buy approval lands during the query suspension; apply must block on the store lock.
        val applyJob = launch { reconciler.apply(listOf(purchased(StoreCatalog.THEME_BUNDLE))) }
        testScheduler.runCurrent()
        assertFalse(store.ownsThemeBundle())

        releaseQuery.complete(Unit)
        reconcileJob.join()
        applyJob.join()

        // The stale reconcile revoked nothing (nothing was purchased yet); the fresh grant survives.
        assertTrue(store.ownsThemeBundle())
    }
}
