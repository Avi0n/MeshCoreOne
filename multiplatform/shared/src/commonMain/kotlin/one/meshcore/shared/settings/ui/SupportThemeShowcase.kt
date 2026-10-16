package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_accessibility_theme_card_locked_label
import one.meshcore.shared.resources.settings_support_accessibility_theme_card_owned_label
import one.meshcore.shared.resources.settings_support_bundle_subtitle
import one.meshcore.shared.resources.settings_support_themes_all_unlocked
import one.meshcore.shared.resources.settings_support_themes_owned
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeAccess
import one.meshcore.shared.theme.ThemeRegistry
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val ALL_UNLOCKED_HERO_SIZE = 44.dp

/** Columns in the theme-preview showcase grid. */
private const val THEME_GRID_COLUMNS = 2

/**
 * The theme-bundle showcase: the all-unlocked celebration when every purchasable theme is owned, else the
 * preview grid plus the single bundle purchase row. Split from the screen body to keep the grid-layout and
 * purchase branches out of the top-level composable's complexity.
 */
@Composable
internal fun ThemeShowcaseSection(
    entitlements: EntitlementStore?,
    bundleDetails: ProductDetails?,
    bundleFallback: String,
    ownedLabel: String,
    purchaseEnabled: Boolean,
    bundlePending: Boolean,
    pendingLabel: String,
    onPurchaseBundle: () -> Unit,
) {
    if (ownsEveryPurchasableTheme(entitlements)) {
        AllUnlockedCard()
        return
    }
    val purchasableThemes = ThemeRegistry.all.filter { !it.isFree }
    purchasableThemes.chunked(THEME_GRID_COLUMNS).forEach { rowThemes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rowThemes.forEach { theme ->
                ThemePreviewCard(
                    theme = theme,
                    owned = ThemeAccess.isAccessible(theme.id, entitlements),
                    modifier = Modifier.weight(1f),
                )
            }
            // Keep the last, short row left-aligned with the same card width as full rows.
            repeat(THEME_GRID_COLUMNS - rowThemes.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
    ProductPurchaseRow(
        details = bundleDetails,
        fallbackTitle = bundleFallback,
        fallbackSubtitle = stringResource(Res.string.settings_support_bundle_subtitle),
        owned = entitlements?.isOwned(StoreCatalog.THEME_BUNDLE) == true,
        ownedLabel = ownedLabel,
        enabled = purchaseEnabled && !bundlePending,
        pendingLabel = if (bundlePending) pendingLabel else null,
        a11yKind = PurchaseRowA11yKind.Bundle,
        onPurchase = onPurchaseBundle,
    )
}

/** Celebration card shown in place of the theme grid once every purchasable theme is owned. */
@Composable
private fun AllUnlockedCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ALL_UNLOCKED_HERO_SIZE),
        )
        Text(
            text = stringResource(Res.string.settings_support_themes_all_unlocked),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A single theme preview in the bundle showcase: swatch + name + locked/owned state. Non-interactive;
 * the bundle row is the only purchase control and applying happens on the Appearance screen.
 */
@Composable
private fun ThemePreviewCard(theme: AppTheme, owned: Boolean, modifier: Modifier = Modifier) {
    val label = appearanceThemeDisplayName(theme.id)
    val stateDescription = if (owned) {
        formattedStringResource(Res.string.settings_support_accessibility_theme_card_owned_label, label)
    } else {
        formattedStringResource(Res.string.settings_support_accessibility_theme_card_locked_label, label)
    }
    ElevatedCard(
        modifier = modifier.clearAndSetSemantics { contentDescription = stateDescription },
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                ThemeSwatch(theme)
                if (!owned) {
                    Icon(
                        imageVector = AppIcons.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (owned) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.settings_support_themes_owned),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: AppTheme) {
    ThemeColorSwatch(
        theme = theme,
        diameter = SUPPORT_THEME_SWATCH_DIAMETER,
        dotSize = SUPPORT_THEME_SWATCH_DOT,
        borderWidth = SUPPORT_THEME_SWATCH_BORDER,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    )
}

private val SUPPORT_THEME_SWATCH_DIAMETER = 44.dp
private val SUPPORT_THEME_SWATCH_DOT = 11.dp
private val SUPPORT_THEME_SWATCH_BORDER = 1.dp
