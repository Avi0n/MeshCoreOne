package one.meshcore.shared.theme

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.billing.EntitlementReconciler
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.OwnedPurchase
import one.meshcore.shared.billing.PurchaseFulfiller
import one.meshcore.shared.billing.PurchaseQueryResult
import one.meshcore.shared.billing.PurchaseState
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PAID_THEME_ID = "ember"

private val noOpFulfiller = object : PurchaseFulfiller {
    override suspend fun acknowledge(purchaseToken: String) = true

    override suspend fun consume(purchaseToken: String) = true
}

private fun bundlePurchase() =
    OwnedPurchase(listOf(StoreCatalog.THEME_BUNDLE), "token", PurchaseState.PURCHASED, isAcknowledged = true)

/**
 * The theme-revert write, exercised through the same seam both revert sites call. Covers the three
 * entitlement states: not yet authoritative (Play has not answered), authoritative and empty
 * (genuine non-owner or refund), authoritative and owning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeRevertPersistenceTest {
    private fun appearanceWith(themeId: String): Pair<InMemoryPreferencesStore, AppearancePreferencesStore> {
        val prefs = InMemoryPreferencesStore(mapOf(PersistenceKeys.SELECTED_THEME_ID to themeId))
        val store = AppearancePreferencesStore(prefs, TestScope(UnconfinedTestDispatcher()))
        store.load()
        return prefs to store
    }

    @Test
    fun coldLaunchBeforePlayAnswersKeepsPaidTheme() = runTest(UnconfinedTestDispatcher()) {
        val (prefs, appearance) = appearanceWith(PAID_THEME_ID)
        // Cold launch: the Play query has not answered, so the owned set is empty but not authoritative.
        val entitlements = EntitlementStore()

        assertFalse(
            ThemeAccess.persistAccessibleThemeId(
                PAID_THEME_ID,
                entitlements,
                appearance,
                billingEnabled = true,
            ),
        )
        assertEquals(PAID_THEME_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertEquals(PAID_THEME_ID, appearance.selectedThemeId.value)
        assertEquals(
            PAID_THEME_ID,
            ThemeAccess.resolveAccessibleThemeId(PAID_THEME_ID, entitlements, billingEnabled = true),
        )
    }

    @Test
    fun playOutageKeepsPaidTheme() = runTest(UnconfinedTestDispatcher()) {
        val (prefs, appearance) = appearanceWith(PAID_THEME_ID)
        val entitlements = EntitlementStore()
        // A failed query is not an owned set; it must leave the selection alone.
        EntitlementReconciler(entitlements, noOpFulfiller).reconcile { PurchaseQueryResult.Unavailable }

        assertFalse(
            ThemeAccess.persistAccessibleThemeId(
                PAID_THEME_ID,
                entitlements,
                appearance,
                billingEnabled = true,
            ),
        )
        assertEquals(PAID_THEME_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
    }

    @Test
    fun refundRevertsAndPersistsDefault() = runTest(UnconfinedTestDispatcher()) {
        val (prefs, appearance) = appearanceWith(PAID_THEME_ID)
        val entitlements = EntitlementStore()
        val reconciler = EntitlementReconciler(entitlements, noOpFulfiller)
        reconciler.reconcile { PurchaseQueryResult.Available(listOf(bundlePurchase())) }
        assertFalse(
            ThemeAccess.persistAccessibleThemeId(
                PAID_THEME_ID,
                entitlements,
                appearance,
                billingEnabled = true,
            ),
        )
        assertEquals(PAID_THEME_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))

        // The refund drops the bundle out of Play's authoritative owned set.
        reconciler.reconcile { PurchaseQueryResult.Available(emptyList()) }
        assertTrue(ThemeAccess.persistAccessibleThemeId(PAID_THEME_ID, entitlements, appearance, billingEnabled = true))

        assertEquals(AppTheme.DEFAULT_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertEquals(AppTheme.DEFAULT_ID, appearance.selectedThemeId.value)
    }

    @Test
    fun authoritativeEmptySetRevertsUnownedTheme() = runTest(UnconfinedTestDispatcher()) {
        val (prefs, appearance) = appearanceWith(PAID_THEME_ID)
        val entitlements = EntitlementStore()
        // Play answered and reports nothing owned: the selection was never paid for.
        EntitlementReconciler(entitlements, noOpFulfiller)
            .reconcile { PurchaseQueryResult.Available(emptyList()) }

        assertTrue(ThemeAccess.persistAccessibleThemeId(PAID_THEME_ID, entitlements, appearance, billingEnabled = true))

        assertEquals(AppTheme.DEFAULT_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
    }

    @Test
    fun ownedPaidThemeIsKeptAndNotRewritten() = runTest(UnconfinedTestDispatcher()) {
        val (prefs, appearance) = appearanceWith(PAID_THEME_ID)
        val entitlements = EntitlementStore()
        EntitlementReconciler(entitlements, noOpFulfiller)
            .reconcile { PurchaseQueryResult.Available(listOf(bundlePurchase())) }

        assertFalse(
            ThemeAccess.persistAccessibleThemeId(
                PAID_THEME_ID,
                entitlements,
                appearance,
                billingEnabled = true,
            ),
        )

        assertEquals(PAID_THEME_ID, prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
    }
}
