package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.PendingPurchase
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_bundle_title
import one.meshcore.shared.resources.settings_support_contact_link
import one.meshcore.shared.resources.settings_support_contributions_footer
import one.meshcore.shared.resources.settings_support_contributions_title
import one.meshcore.shared.resources.settings_support_header_body
import one.meshcore.shared.resources.settings_support_pending_banner
import one.meshcore.shared.resources.settings_support_pending_button
import one.meshcore.shared.resources.settings_support_refund_link
import one.meshcore.shared.resources.settings_support_restore_button
import one.meshcore.shared.resources.settings_support_restore_syncing
import one.meshcore.shared.resources.settings_support_themes_owned
import one.meshcore.shared.resources.settings_support_themes_purchased_footer
import one.meshcore.shared.resources.settings_support_themes_title
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import org.jetbrains.compose.resources.stringResource

internal data class SupportSettingsContentState(
    val demoMode: DemoModeStore?,
    val catalog: Map<String, ProductDetails>,
    val entitlements: EntitlementStore?,
    val pendingPurchase: PendingPurchase?,
    val ownsThemeBundle: Boolean,
    val purchaseEnabled: Boolean,
    val isRestoring: Boolean,
)

internal data class SupportSettingsContentActions(
    val onPurchaseBundle: (displayName: String) -> Unit,
    val onLaunchTip: (tipId: String, displayName: String) -> Unit,
    val onConfirmHighValue: (ConfirmingTip) -> Unit,
    val onRestore: () -> Unit,
    val onRequestRefund: (() -> Unit)?,
    val onContactSupport: (() -> Unit)?,
)

@Composable
internal fun SupportSettingsContent(
    state: SupportSettingsContentState,
    actions: SupportSettingsContentActions,
    modifier: Modifier = Modifier,
) {
    val bundleFallback = stringResource(Res.string.settings_support_bundle_title)
    val ownedLabel = stringResource(Res.string.settings_support_themes_owned)
    val pendingButton = stringResource(Res.string.settings_support_pending_button)

    Column(
        modifier.preferenceHubEdgeScroll(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_support_header_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { state.demoMode?.registerUnlockTap() },
        )
        state.demoMode?.let { DemoModeSection(it) }
        if (state.pendingPurchase != null) {
            PreferenceHubSection(applyHorizontalPadding = false) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(Res.string.settings_support_pending_banner),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = SUPPORT_PENDING_BANNER_ICON,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        PreferenceHubSection(
            title = stringResource(Res.string.settings_support_themes_title),
            applyHorizontalPadding = false,
        ) {
            ThemeShowcaseSection(
                entitlements = state.entitlements,
                bundleDetails = state.catalog[StoreCatalog.THEME_BUNDLE],
                bundleFallback = bundleFallback,
                ownedLabel = ownedLabel,
                purchaseEnabled = state.purchaseEnabled,
                bundlePending = state.pendingPurchase?.productId == StoreCatalog.THEME_BUNDLE,
                pendingLabel = pendingButton,
                onPurchaseBundle = {
                    actions.onPurchaseBundle(
                        supportStoreTitle(state.catalog[StoreCatalog.THEME_BUNDLE]?.title, bundleFallback),
                    )
                },
            )
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_support_themes_purchased_footer),
            applyHorizontalPadding = false,
        )
        PreferenceHubSection(
            title = stringResource(Res.string.settings_support_contributions_title),
            applyHorizontalPadding = false,
        ) {
            TipsSection(
                catalog = state.catalog,
                entitlements = state.entitlements,
                ownedLabel = ownedLabel,
                purchaseEnabled = state.purchaseEnabled,
                onLaunchTip = { tipId ->
                    actions.onLaunchTip(
                        tipId,
                        supportStoreTitle(state.catalog[tipId]?.title, tipId),
                    )
                },
                onConfirmHighValue = actions.onConfirmHighValue,
            )
        }
        OutlinedButton(
            onClick = actions.onRestore,
            enabled = !state.isRestoring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isRestoring) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.settings_support_restore_syncing))
            } else {
                Text(stringResource(Res.string.settings_support_restore_button))
            }
        }
        // Show Request a refund only when the theme bundle is owned. Tips are consumable and have no
        // in-app refund path. Play has no StoreKit refund sheet; the link opens order history.
        if (state.ownsThemeBundle && actions.onRequestRefund != null) {
            OutlinedButton(
                onClick = actions.onRequestRefund,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_support_refund_link))
            }
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_support_contributions_footer),
            applyHorizontalPadding = false,
        )
        actions.onContactSupport?.let { onContactSupport ->
            PreferenceHubSection(applyHorizontalPadding = false) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.settings_support_contact_link)) },
                    trailingContent = {
                        Icon(
                            AppIcons.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onContactSupport),
                )
            }
        }
    }
}
