package one.meshcore.shared.settings.ui

import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.PurchaseErrorKind
import one.meshcore.shared.billing.PurchaseResult
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_error_network_unavailable
import one.meshcore.shared.resources.settings_support_error_not_entitled
import one.meshcore.shared.resources.settings_support_error_product_not_found
import one.meshcore.shared.resources.settings_support_error_purchase_failed
import one.meshcore.shared.resources.settings_support_error_storefront_unavailable
import one.meshcore.shared.resources.settings_support_error_unsupported
import one.meshcore.shared.resources.settings_support_error_verification_failed
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Support-screen purchase gating, ported from iOS `ContributionsSection` (high-value = massive/epic)
 * and `ThemesPurchaseSection.ownsEveryTheme` (the All Unlocked celebration).
 */
class SupportPurchaseLogicTest {
    @Test
    fun onlyMassiveAndEpicTipsConfirmFirst() {
        assertEquals(setOf(StoreCatalog.Tip.TIP_5, StoreCatalog.Tip.TIP_6), HIGH_VALUE_TIP_IDS)
        assertTrue(isHighValueTip(StoreCatalog.Tip.TIP_5))
        assertTrue(isHighValueTip(StoreCatalog.Tip.TIP_6))
        listOf(
            StoreCatalog.Tip.TIP_1,
            StoreCatalog.Tip.TIP_2,
            StoreCatalog.Tip.TIP_3,
            StoreCatalog.Tip.TIP_4,
        ).forEach { assertFalse(isHighValueTip(it)) }
    }

    @Test
    fun noStoreTreatsEveryThemeAsUnlocked() {
        // Desktop / test builds without a billing surface preview the full palette.
        assertTrue(ownsEveryPurchasableTheme(null, billingEnabled = true))
    }

    @Test
    fun emptyEntitlementsFollowTheBillingFlag() {
        val store = EntitlementStore()
        assertTrue(ownsEveryPurchasableTheme(store, billingEnabled = false))
        assertFalse(ownsEveryPurchasableTheme(store, billingEnabled = true))
        assertEquals(!SideloadDistribution.BILLING_ENABLED, ownsEveryPurchasableTheme(store))
    }

    @Test
    fun themeBundleUnlocksEveryTheme() {
        val store = EntitlementStore().apply { grant(StoreCatalog.THEME_BUNDLE) }
        assertTrue(ownsEveryPurchasableTheme(store, billingEnabled = true))
    }

    @Test
    fun ownedAndPendingDropThePriceButton() {
        assertTrue(purchaseRowShowsPriceButton(owned = false, pending = false))
        assertFalse(purchaseRowShowsPriceButton(owned = true, pending = false))
        assertFalse(purchaseRowShowsPriceButton(owned = false, pending = true))
    }

    @Test
    fun verifiedPurchasePresentsThanksAndHidesStatus() {
        assertTrue(shouldPresentPurchaseThanks(PurchaseResult.Success("themes.bundle")))
    }

    @Test
    fun pendingCancelAndErrorDoNotPresentThanks() {
        assertFalse(shouldPresentPurchaseThanks(PurchaseResult.Pending("tip.1")))
        assertFalse(shouldPresentPurchaseThanks(PurchaseResult.Cancelled("tip.1")))
        assertFalse(shouldPresentPurchaseThanks(PurchaseResult.Error("tip.1", "network")))
    }

    @Test
    fun storeErrorsUseIosUserFacingKeys() {
        assertEquals(
            Res.string.settings_support_error_product_not_found,
            storeErrorResource(PurchaseErrorKind.PRODUCT_NOT_FOUND),
        )
        assertEquals(
            Res.string.settings_support_error_verification_failed,
            storeErrorResource(PurchaseErrorKind.VERIFICATION_FAILED),
        )
        assertEquals(
            Res.string.settings_support_error_not_entitled,
            storeErrorResource(PurchaseErrorKind.NOT_ENTITLED),
        )
        assertEquals(
            Res.string.settings_support_error_network_unavailable,
            storeErrorResource(PurchaseErrorKind.NETWORK_UNAVAILABLE),
        )
        assertEquals(
            Res.string.settings_support_error_storefront_unavailable,
            storeErrorResource(PurchaseErrorKind.STOREFRONT_UNAVAILABLE),
        )
        assertEquals(
            Res.string.settings_support_error_unsupported,
            storeErrorResource(PurchaseErrorKind.UNSUPPORTED),
        )
        assertEquals(
            Res.string.settings_support_error_purchase_failed,
            storeErrorResource(PurchaseErrorKind.PURCHASE_FAILED),
        )
        assertEquals(
            emptyList(),
            storeErrorFormatArgs(
                PurchaseResult.Error("tip.1", "debug", PurchaseErrorKind.NETWORK_UNAVAILABLE),
            ),
        )
        assertEquals(
            listOf("debug"),
            storeErrorFormatArgs(PurchaseResult.Error("tip.1", "debug")),
        )
    }

    @Test
    fun contributionRowLabelJoinsNameAndPrice() {
        assertEquals(
            "Coffee, $2.99",
            contributionRowAccessibilityLabel("Coffee", "$2.99", "%1\$s, %2\$s"),
        )
        assertEquals(
            "Lunch, ",
            contributionRowAccessibilityLabel("Lunch", "", "%1\$s, %2\$s"),
        )
    }

    @Test
    fun bundleCardLabelUsesLoadingUntilPriceArrives() {
        assertEquals(
            "loading",
            bundleCardAccessibilityLabel(
                pending = false,
                displayPrice = null,
                loadingLabel = "loading",
                lockedTemplate = "bundle, %s",
            ),
        )
        assertEquals(
            "bundle, $4.99",
            bundleCardAccessibilityLabel(
                pending = false,
                displayPrice = "$4.99",
                loadingLabel = "loading",
                lockedTemplate = "bundle, %s",
            ),
        )
        assertEquals(
            "bundle, $4.99",
            bundleCardAccessibilityLabel(
                pending = true,
                displayPrice = "$4.99",
                loadingLabel = "loading",
                lockedTemplate = "bundle, %s",
            ),
        )
    }

    @Test
    fun bundleCardActionableRequiresPriceAndNotPending() {
        assertFalse(bundleCardIsActionable(pending = true, displayPrice = "$1"))
        assertFalse(bundleCardIsActionable(pending = false, displayPrice = null))
        assertTrue(bundleCardIsActionable(pending = false, displayPrice = "$1"))
    }

    @Test
    fun pendingBannerUsesClockGlyph() {
        assertEquals(AppIcons.AccessTime, SUPPORT_PENDING_BANNER_ICON)
    }
}
