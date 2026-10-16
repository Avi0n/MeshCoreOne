package one.meshcore.shared.settings.ui

import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.theme.ThemeAccess
import one.meshcore.shared.theme.ThemeRegistry
import one.meshcore.shared.ui.formatTemplate

/** Tip product ids that require a confirm step before purchase (iOS massive / epic tiers). */
val HIGH_VALUE_TIP_IDS: Set<String> = setOf(StoreCatalog.Tip.TIP_5, StoreCatalog.Tip.TIP_6)

/** Whether [tipId] is a high-value tip that confirms before launching the purchase. */
fun isHighValueTip(tipId: String): Boolean = tipId in HIGH_VALUE_TIP_IDS

/** VoiceOver label for a tip row: display name then price, matching iOS `ContributionRow`. */
fun contributionRowAccessibilityLabel(displayName: String, displayPrice: String, template: String): String =
    formatTemplate(template, listOf(displayName, displayPrice))

/**
 * Bundle-card spoken label. Loading only when the price has not arrived and nothing is pending,
 * matching iOS `ThemeBundleCard.accessibilityLabel`.
 */
fun bundleCardAccessibilityLabel(
    pending: Boolean,
    displayPrice: String?,
    loadingLabel: String,
    lockedTemplate: String,
): String {
    if (!pending && displayPrice == null) return loadingLabel
    return formatTemplate(lockedTemplate, listOf(displayPrice ?: ""))
}

/** True once the bundle card is an activatable purchase control. */
fun bundleCardIsActionable(pending: Boolean, displayPrice: String?): Boolean = !pending && displayPrice != null

/** The price button is the only trailing control; owned/pending are not dummy trailing facts. */
internal fun purchaseRowShowsPriceButton(owned: Boolean, pending: Boolean): Boolean = !owned && !pending

/**
 * Whether every purchasable theme is accessible. Sideload billing off unlocks them without a grant.
 * Pass [billingEnabled] true to apply the Play ownership rule.
 */
fun ownsEveryPurchasableTheme(
    entitlements: EntitlementStore?,
    billingEnabled: Boolean = SideloadDistribution.BILLING_ENABLED,
): Boolean = ThemeRegistry.all.filter { !it.isFree }.all { theme ->
    ThemeAccess.isAccessible(theme.id, entitlements, billingEnabled)
}
