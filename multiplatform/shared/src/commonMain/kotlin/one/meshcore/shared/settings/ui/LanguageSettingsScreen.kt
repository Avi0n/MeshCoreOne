package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.chat.translation.TranslationTargetPreference
import one.meshcore.shared.chat.translation.platformLanguageDisplayName
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_language_app_language_accessibility_hint
import one.meshcore.shared.resources.android_settings_language_default_translation_app_footer
import one.meshcore.shared.resources.settings_language_app_language_footer
import one.meshcore.shared.resources.settings_language_app_language_title
import one.meshcore.shared.resources.settings_language_default_translation_app_title
import one.meshcore.shared.resources.settings_language_translate_into_footer
import one.meshcore.shared.resources.settings_language_translate_into_header
import one.meshcore.shared.resources.settings_language_translation_offers_off_footer
import one.meshcore.shared.resources.settings_language_translation_offers_title
import one.meshcore.shared.settings.TranslationTargetPreferencesStore
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Language: app locale (system picker) plus in-bubble Translate target, matching iOS
 * `LanguageSettingsView`.
 */
@Composable
fun LanguageSettingsScreen(
    store: TranslationTargetPreferencesStore,
    onOpenAppLanguage: () -> Unit,
    onOpenTranslateInto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.adoptOverlaySentinel() }
    val displayLocale = Locale.current.language
    val appLanguageName = platformLanguageDisplayName(
        code = platformPreferredLanguage(),
        inLocale = displayLocale,
    )
    val detail = TranslationTargetPreference(rawValue = prefs.languageRawValue)
        .detailLabel(appLocaleIdentifier = platformPreferredLanguage(), displayLocale = displayLocale)
    val overlay = prefs.usesSystemOverlay

    Column(modifier.preferenceHubScroll()) {
        PreferenceHubSection {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_language_app_language_title)) },
                supportingContent = { Text(appLanguageName) },
                trailingContent = {
                    Icon(
                        AppIcons.Link,
                        contentDescription = stringResource(
                            Res.string.android_settings_language_app_language_accessibility_hint,
                        ),
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAppLanguage),
            )
        }
        PreferenceHubFooter(stringResource(Res.string.settings_language_app_language_footer))

        PreferenceHubSection(modifier = Modifier.padding(top = PreferenceHubSectionGap)) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_language_translation_offers_title),
                checked = prefs.translationOffersEnabled,
                onCheckedChange = store::setTranslationOffersEnabled,
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_language_translate_into_header)) },
                supportingContent = { Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable(
                    enabled = prefs.translationOffersEnabled && !overlay,
                    onClick = onOpenTranslateInto,
                ),
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_language_default_translation_app_title),
                checked = overlay,
                onCheckedChange = store::setUseDefaultTranslationApp,
                enabled = prefs.translationOffersEnabled,
            )
        }
        PreferenceHubFooter(
            stringResource(
                if (!prefs.translationOffersEnabled) {
                    Res.string.settings_language_translation_offers_off_footer
                } else if (overlay) {
                    Res.string.android_settings_language_default_translation_app_footer
                } else {
                    Res.string.settings_language_translate_into_footer
                },
            ),
        )
    }
}
