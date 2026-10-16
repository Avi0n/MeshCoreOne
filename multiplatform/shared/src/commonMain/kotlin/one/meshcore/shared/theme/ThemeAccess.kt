package one.meshcore.shared.theme

import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.SideloadDistribution

/**
 * Entitlement gates for named themes. Mirrors iOS `ThemeService.isAccessible` / refund revert:
 * free default is always available; paid themes need the bundle or the individual product id.
 *
 * When [entitlements] is null (desktop / tests without a store), every theme is treated as accessible
 * so the palette catalog stays previewable without a billing surface.
 *
 * A sideload build has no Play entitlement. [billingEnabled] false unlocks every named theme, matching
 * the iOS `SIDELOAD` branch. Pass true to exercise the Play ownership rule.
 */
object ThemeAccess {
    fun productIdForTheme(themeId: String): String? = if (themeId == AppTheme.DEFAULT_ID) null else "theme_$themeId"

    fun isAccessible(
        themeId: String,
        entitlements: EntitlementStore?,
        billingEnabled: Boolean = SideloadDistribution.BILLING_ENABLED,
    ): Boolean {
        if (!billingEnabled && ThemeRegistry.all.any { it.id == themeId }) return true
        val theme = ThemeRegistry.byId(themeId)
        val productId = productIdForTheme(themeId)
        return theme.isFree ||
            entitlements == null ||
            productId == null ||
            entitlements.ownsThemeBundle() ||
            entitlements.isOwned(productId)
    }

    /**
     * Returns [selectedThemeId] when still owned, otherwise [AppTheme.DEFAULT_ID]. Call after
     * entitlement revoke/restore so a refunded theme does not keep applying.
     *
     * A selection is dropped only once [EntitlementStore.isAuthoritative] holds. Before Play answers,
     * an empty owned set means "still asking" — every cold launch passes through that state, and
     * treating it as "owns nothing" reverts the paid theme the buyer chose. The revert is unrecoverable
     * because it is persisted and nothing re-selects the theme when the grant lands seconds later.
     */
    fun resolveAccessibleThemeId(
        selectedThemeId: String,
        entitlements: EntitlementStore?,
        billingEnabled: Boolean = SideloadDistribution.BILLING_ENABLED,
    ): String = if (
        isAccessible(selectedThemeId, entitlements, billingEnabled) ||
        entitlements?.isAuthoritative?.value == false
    ) {
        selectedThemeId
    } else {
        AppTheme.DEFAULT_ID
    }

    /**
     * Persists the free default over a selection the user is not entitled to. Call from an effect
     * keyed on the selection, the owned set and [EntitlementStore.isAuthoritative], so a refund
     * reverts the active theme without a restart.
     * Returns true when the selection was rewritten to default, so the host can speak
     * Theme reverted to the default.
     */
    fun persistAccessibleThemeId(
        selectedThemeId: String,
        entitlements: EntitlementStore?,
        appearance: AppearancePreferencesStore?,
        billingEnabled: Boolean = SideloadDistribution.BILLING_ENABLED,
    ): Boolean {
        val resolved = resolveAccessibleThemeId(selectedThemeId, entitlements, billingEnabled)
        if (resolved != selectedThemeId) {
            appearance?.setSelectedThemeId(resolved)
            return true
        }
        return false
    }
}
