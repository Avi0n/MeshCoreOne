package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_accessibility_bundle_card_loading_label
import one.meshcore.shared.resources.settings_support_accessibility_bundle_card_locked_hint
import one.meshcore.shared.resources.settings_support_accessibility_bundle_card_locked_label
import one.meshcore.shared.resources.settings_support_accessibility_contribution_row_hint
import one.meshcore.shared.resources.settings_support_accessibility_contribution_row_label
import one.meshcore.shared.resources.settings_support_tip_1
import one.meshcore.shared.resources.settings_support_tip_2
import one.meshcore.shared.resources.settings_support_tip_3
import one.meshcore.shared.resources.settings_support_tip_4
import one.meshcore.shared.resources.settings_support_tip_5
import one.meshcore.shared.resources.settings_support_tip_6
import org.jetbrains.compose.resources.stringResource

/** Shown in place of a price when Play Billing hasn't resolved a product's details. */
private const val PRICE_UNAVAILABLE = "—"

/** Localized tier name shown when Play Billing product details are unavailable (iOS StoreKit displayName parity). */
@Composable
private fun tipFallbackTitle(tipId: String): String = stringResource(
    when (tipId) {
        StoreCatalog.Tip.TIP_1 -> Res.string.settings_support_tip_1
        StoreCatalog.Tip.TIP_2 -> Res.string.settings_support_tip_2
        StoreCatalog.Tip.TIP_3 -> Res.string.settings_support_tip_3
        StoreCatalog.Tip.TIP_4 -> Res.string.settings_support_tip_4
        StoreCatalog.Tip.TIP_5 -> Res.string.settings_support_tip_5
        else -> Res.string.settings_support_tip_6
    },
)

/**
 * The tip-jar rows, one per catalog tier. High-value tips route through [onConfirmHighValue] (the confirm
 * dialog) before purchase; the rest launch straight through [onLaunchTip]. Split from the screen body so
 * the per-tier branch stays out of the top-level composable's complexity.
 */
@Composable
internal fun TipsSection(
    catalog: Map<String, ProductDetails>,
    entitlements: EntitlementStore?,
    ownedLabel: String,
    purchaseEnabled: Boolean,
    onLaunchTip: (String) -> Unit,
    onConfirmHighValue: (ConfirmingTip) -> Unit,
) {
    StoreCatalog.Tip.all.forEach { tipId ->
        val tipName = tipFallbackTitle(tipId)
        ProductPurchaseRow(
            details = catalog[tipId],
            fallbackTitle = tipName,
            fallbackSubtitle = null,
            owned = entitlements?.isOwned(tipId) == true,
            ownedLabel = ownedLabel,
            enabled = purchaseEnabled,
            pendingLabel = null,
            a11yKind = PurchaseRowA11yKind.Contribution,
            onPurchase = {
                // High-value tips confirm first, mirroring iOS `requiresConfirmation`, to catch a
                // fat-finger purchase before the Play sheet opens.
                if (isHighValueTip(tipId)) {
                    onConfirmHighValue(
                        ConfirmingTip(
                            id = tipId,
                            name = tipName,
                            price = catalog[tipId]?.formattedPrice?.takeIf { it.isNotBlank() } ?: tipName,
                        ),
                    )
                } else {
                    onLaunchTip(tipId)
                }
            },
        )
    }
}

@Composable
internal fun ProductPurchaseRow(
    details: ProductDetails?,
    fallbackTitle: String,
    fallbackSubtitle: String?,
    owned: Boolean,
    ownedLabel: String,
    enabled: Boolean,
    pendingLabel: String?,
    a11yKind: PurchaseRowA11yKind,
    onPurchase: () -> Unit,
) {
    val title = purchaseRowTitle(details, fallbackTitle)
    val price = purchaseRowPrice(details)
    val pending = pendingLabel != null
    val supportingStatus = purchaseRowSupportingStatus(
        owned = owned,
        ownedLabel = ownedLabel,
        pendingLabel = pendingLabel,
        fallbackSubtitle = fallbackSubtitle,
        price = price,
    )
    val accessibility = purchaseRowAccessibility(
        PurchaseRowAccessibilityInput(
            kind = a11yKind,
            title = title,
            price = price,
            pending = pending,
            owned = owned,
            enabled = enabled,
            text = PurchaseRowAccessibilityText(
                contributionTemplate = stringResource(
                    Res.string.settings_support_accessibility_contribution_row_label,
                ),
                bundleLoadingLabel = stringResource(
                    Res.string.settings_support_accessibility_bundle_card_loading_label,
                ),
                bundleLockedTemplate = stringResource(
                    Res.string.settings_support_accessibility_bundle_card_locked_label,
                ),
                contributionHint = stringResource(
                    Res.string.settings_support_accessibility_contribution_row_hint,
                ),
                bundleLockedHint = stringResource(
                    Res.string.settings_support_accessibility_bundle_card_locked_hint,
                ),
            ),
        ),
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingStatus?.let { status ->
            {
                Text(
                    text = status,
                    color = if (owned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        trailingContent = if (purchaseRowShowsPriceButton(owned = owned, pending = pending)) {
            {
                FilledTonalButton(
                    onClick = onPurchase,
                    enabled = purchaseRowButtonEnabled(enabled, price),
                ) {
                    Text(purchaseRowButtonLabel(price, PRICE_UNAVAILABLE))
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clearAndSetSemantics { contentDescription = accessibility.label }
            .then(
                if (accessibility.enabled) {
                    Modifier.clickable(
                        onClickLabel = accessibility.hint,
                        role = Role.Button,
                        onClick = onPurchase,
                    )
                } else {
                    Modifier
                },
            ),
    )
}
