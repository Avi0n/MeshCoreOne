package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_appearance_default_uses_wallpaper
import one.meshcore.shared.resources.settings_appearance_accessibility_swatch_dark_only
import one.meshcore.shared.resources.settings_appearance_accessibility_swatch_dual_mode
import one.meshcore.shared.resources.settings_appearance_accessibility_theme_card_owned_hint
import one.meshcore.shared.resources.settings_appearance_accessibility_theme_card_owned_label
import one.meshcore.shared.resources.settings_appearance_accessibility_theme_card_selected_label
import one.meshcore.shared.resources.settings_appearance_more_themes_link
import one.meshcore.shared.resources.settings_appearance_scheme_header
import one.meshcore.shared.resources.settings_appearance_themes_header
import one.meshcore.shared.resources.settings_support_accessibility_theme_card_locked_label
import one.meshcore.shared.resources.settings_support_theme_default
import one.meshcore.shared.resources.settings_support_theme_ember
import one.meshcore.shared.resources.settings_support_theme_fern
import one.meshcore.shared.resources.settings_support_theme_lavender
import one.meshcore.shared.resources.settings_support_theme_marine
import one.meshcore.shared.resources.settings_support_theme_olive
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.theme.ThemeAccess
import one.meshcore.shared.theme.ThemeRegistry
import one.meshcore.shared.theme.platformDefaultThemeUsesWallpaperColors
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubRadioRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.preferenceHubScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Appearance: light/dark/system radio group and named theme cards with full M3 roles.
 * Paid themes require [EntitlementStore] theme-bundle ownership (unlocked for free default).
 * [onPurchaseMoreThemes] opens Support when any theme remains locked.
 */
@Composable
fun AppearanceSettingsScreen(
    store: AppearancePreferencesStore,
    entitlements: EntitlementStore? = null,
    onPurchaseMoreThemes: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme by store.colorScheme.collectAsStateWithLifecycle()
    val themeId by store.selectedThemeId.collectAsStateWithLifecycle()
    val ownedProducts = entitlements?.ownedProducts?.collectAsStateWithLifecycle()?.value.orEmpty()
    // Collected, not just read: a query that answers "nothing owned" leaves the owned set unchanged,
    // so this flag is the only signal that the revert may now run.
    val entitlementsAuthoritative = entitlements?.isAuthoritative?.collectAsStateWithLifecycle()?.value ?: true
    val haptics = rememberAppHaptics()

    // Refund / entitlement loss: persist default when the selected paid theme is no longer owned.
    LaunchedEffect(themeId, ownedProducts, entitlementsAuthoritative) {
        ThemeAccess.persistAccessibleThemeId(themeId, entitlements, store)
    }

    Column(modifier.preferenceHubScroll()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_appearance_scheme_header),
            contentModifier = Modifier.selectableGroup(),
        ) {
            colorSchemeChoices().forEach { (pref, label) ->
                PreferenceHubRadioRow(
                    label = label,
                    selected = scheme == pref,
                    onSelect = {
                        haptics.selection()
                        store.setColorScheme(pref)
                    },
                )
            }
        }

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_appearance_themes_header),
            contentModifier = Modifier.selectableGroup(),
        ) {
            val unlockedById = ThemeRegistry.all.associate { theme ->
                theme.id to ThemeAccess.isAccessible(theme.id, entitlements)
            }
            Column {
                ThemeRegistry.all.forEach { theme ->
                    val unlocked = unlockedById.getValue(theme.id)
                    val label = appearanceThemeDisplayName(theme.id)
                    val spokenName = themeSwatchSpokenName(
                        themeName = label,
                        isDualMode = theme.isDualMode,
                        dualTemplate = stringResource(Res.string.settings_appearance_accessibility_swatch_dual_mode),
                        darkOnlyTemplate = stringResource(
                            Res.string.settings_appearance_accessibility_swatch_dark_only,
                        ),
                    )
                    val lockedDescription = formatTemplate(
                        stringResource(Res.string.settings_support_accessibility_theme_card_locked_label),
                        listOf(spokenName),
                    )
                    val selectedDescription = formatTemplate(
                        stringResource(Res.string.settings_appearance_accessibility_theme_card_selected_label),
                        listOf(spokenName),
                    )
                    val ownedDescription = formatTemplate(
                        stringResource(Res.string.settings_appearance_accessibility_theme_card_owned_label),
                        listOf(spokenName),
                    )
                    ThemeCardRow(
                        theme = theme,
                        label = label,
                        selected = themeId == theme.id,
                        enabled = unlocked,
                        caption = defaultThemeWallpaperCaption(
                            themeId = theme.id,
                            usesWallpaper = platformDefaultThemeUsesWallpaperColors(),
                            caption = stringResource(Res.string.android_appearance_default_uses_wallpaper),
                        ),
                        description = when {
                            !unlocked -> lockedDescription
                            themeId == theme.id -> selectedDescription
                            else -> ownedDescription
                        },
                        applyHint = if (unlocked && themeId != theme.id) {
                            stringResource(Res.string.settings_appearance_accessibility_theme_card_owned_hint)
                        } else {
                            null
                        },
                        onSelect = {
                            if (unlocked) {
                                haptics.selection()
                                store.setSelectedThemeId(theme.id)
                            }
                        },
                    )
                }
                if (unlockedById.values.any { !it } && onPurchaseMoreThemes != null) {
                    OutlinedButton(
                        onClick = onPurchaseMoreThemes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PreferenceHubCardPadding),
                    ) {
                        Text(stringResource(Res.string.settings_appearance_more_themes_link))
                    }
                }
            }
        }
    }
}

@Composable
internal fun appearanceThemeDisplayName(id: String): String = appearanceThemeDisplayName(
    id = id,
    names = AppearanceThemeNames(
        default = stringResource(Res.string.settings_support_theme_default),
        ember = stringResource(Res.string.settings_support_theme_ember),
        fern = stringResource(Res.string.settings_support_theme_fern),
        marine = stringResource(Res.string.settings_support_theme_marine),
        olive = stringResource(Res.string.settings_support_theme_olive),
        lavender = stringResource(Res.string.settings_support_theme_lavender),
    ),
)

/**
 * User-facing theme name matching iOS `Theme.localizedName`. Proper-noun palettes stay untranslated.
 */
internal fun appearanceThemeDisplayName(id: String, names: AppearanceThemeNames): String = when (id) {
    AppTheme.DEFAULT_ID -> names.default
    "ember" -> names.ember
    "fern" -> names.fern
    "marine" -> names.marine
    "olive" -> names.olive
    "lavender" -> names.lavender
    "sakura" -> APPEARANCE_THEME_SAKURA
    "solarized" -> APPEARANCE_THEME_SOLARIZED
    "nord" -> APPEARANCE_THEME_NORD
    "catppuccin" -> APPEARANCE_THEME_CATPPUCCIN
    else -> id
}

internal fun defaultThemeWallpaperCaption(themeId: String, usesWallpaper: Boolean, caption: String): String? =
    if (themeId == AppTheme.DEFAULT_ID && usesWallpaper) caption else null

@Composable
private fun ThemeCardRow(
    theme: AppTheme,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
    description: String,
    applyHint: String? = null,
    caption: String? = null,
) {
    val canSelect = enabled && !selected
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (caption != null || !enabled) {
            {
                Column {
                    if (caption != null) {
                        Text(caption)
                    }
                    if (!enabled) {
                        Text(stringResource(Res.string.settings_appearance_more_themes_link))
                    }
                }
            }
        } else {
            null
        },
        leadingContent = { ThemeSwatch(theme = theme, selected = selected) },
        trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LOCKED_THEME_ALPHA)
            .clickable(
                enabled = canSelect,
                onClickLabel = applyHint,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
            },
    )
}

@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean) {
    ThemeColorSwatch(
        theme = theme,
        diameter = APPEARANCE_THEME_SWATCH_DIAMETER,
        dotSize = APPEARANCE_THEME_SWATCH_DOT,
        borderWidth = if (selected) APPEARANCE_THEME_SWATCH_SELECTED_BORDER else APPEARANCE_THEME_SWATCH_REST_BORDER,
        borderColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
    )
}

private val APPEARANCE_THEME_SWATCH_DIAMETER = 40.dp
private val APPEARANCE_THEME_SWATCH_DOT = 10.dp
private val APPEARANCE_THEME_SWATCH_SELECTED_BORDER = 2.dp
private val APPEARANCE_THEME_SWATCH_REST_BORDER = 1.dp

private const val LOCKED_THEME_ALPHA = 0.55f

internal const val APPEARANCE_THEME_SAKURA = "Sakura"
internal const val APPEARANCE_THEME_SOLARIZED = "Solarized"
internal const val APPEARANCE_THEME_NORD = "Nord"
internal const val APPEARANCE_THEME_CATPPUCCIN = "Catppuccin"

/** Ember pins dark; every other theme follows the scheme picker. */
internal val AppTheme.isDualMode: Boolean get() = forceDark == null

/** TalkBack name plus light/dark variants or dark only. */
internal fun themeSwatchSpokenName(
    themeName: String,
    isDualMode: Boolean,
    dualTemplate: String,
    darkOnlyTemplate: String,
): String = formatTemplate(
    if (isDualMode) dualTemplate else darkOnlyTemplate,
    listOf(themeName),
)
