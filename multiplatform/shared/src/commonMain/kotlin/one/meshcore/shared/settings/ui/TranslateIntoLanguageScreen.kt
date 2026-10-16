package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.chat.translation.MessageLanguageDetector
import one.meshcore.shared.chat.translation.TranslationTargetPreference
import one.meshcore.shared.chat.translation.platformOnDeviceTranslationCatalog
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.chat.translation.platformPreferredLanguageTags
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_language_all_header
import one.meshcore.shared.resources.settings_language_default_translation_app_title
import one.meshcore.shared.resources.settings_language_match_app_language
import one.meshcore.shared.resources.settings_language_search_no_results
import one.meshcore.shared.resources.settings_language_search_no_results_description
import one.meshcore.shared.resources.settings_language_search_prompt
import one.meshcore.shared.resources.settings_language_search_unavailable
import one.meshcore.shared.resources.settings_language_suggested_header
import one.meshcore.shared.resources.settings_language_translate_into_list_footer
import one.meshcore.shared.settings.TranslationTargetPreferencesStore
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.preferenceHubFill
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Language → Translate Into: on-device catalog plus unavailable-language search,
 * matching iOS `TranslateIntoLanguageView`.
 */
@Composable
fun TranslateIntoLanguageScreen(
    store: TranslationTargetPreferencesStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs by store.state.collectAsStateWithLifecycle()
    val displayLocale = Locale.current.language
    val selected = TranslationTargetPreference(rawValue = prefs.languageRawValue)
    val catalog = remember(prefs.languageRawValue) {
        translateIntoCatalogCodes(
            selected = selected,
            onDeviceCodes = platformOnDeviceTranslationCatalog(),
        )
    }
    val suggested = remember(catalog) {
        TranslationTargetPreference.suggestedLanguageCodes(
            preferredLanguages = platformPreferredLanguageTags(),
            appLocaleIdentifier = platformPreferredLanguage(),
            catalog = catalog,
        )
    }
    val remaining = remember(catalog, suggested, displayLocale) {
        translateIntoRemainingCodes(catalog, suggested, displayLocale)
    }
    var query by remember { mutableStateOf("") }
    val search = translateIntoSearch(query, catalog, displayLocale)
    val haptics = rememberAppHaptics()
    fun chooseLanguage(raw: String) {
        haptics.selection()
        store.setLanguageRawValue(raw)
    }

    Column(modifier.preferenceHubFill()) {
        AppSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(Res.string.settings_language_search_prompt),
        )
        if (translateIntoSearchIsEmpty(search)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(Res.string.settings_language_search_no_results),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatTemplate(
                        stringResource(Res.string.settings_language_search_no_results_description),
                        listOf(query),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PreferenceHubFooter(stringResource(Res.string.settings_language_translate_into_list_footer))
        } else {
            Column(preferenceHubFormScroll()) {
                if (!search.searching) {
                    PreferenceHubSection {
                        LanguageChoiceRow(
                            title = stringResource(Res.string.settings_language_match_app_language),
                            selected = selected.isMatchAppLanguage,
                            onClick = { chooseLanguage(TranslationTargetPreference.MATCH_APP_LANGUAGE) },
                        )
                    }
                    if (suggested.isNotEmpty()) {
                        PreferenceHubSection(
                            modifier = Modifier.padding(top = PreferenceHubSectionGap),
                            title = stringResource(Res.string.settings_language_suggested_header),
                        ) {
                            suggested.forEach { code ->
                                LanguageCodeRow(
                                    code = code,
                                    displayLocale = displayLocale,
                                    selected = isCatalogLanguageSelected(selected, code),
                                    onClick = { chooseLanguage(code) },
                                )
                            }
                        }
                    }
                    PreferenceHubSection(
                        modifier = Modifier.padding(top = PreferenceHubSectionGap),
                        title = stringResource(Res.string.settings_language_all_header),
                    ) {
                        remaining.forEach { code ->
                            LanguageCodeRow(
                                code = code,
                                displayLocale = displayLocale,
                                selected = isCatalogLanguageSelected(selected, code),
                                onClick = { chooseLanguage(code) },
                            )
                        }
                    }
                } else {
                    PreferenceHubSection {
                        search.matches.forEach { code ->
                            LanguageCodeRow(
                                code = code,
                                displayLocale = displayLocale,
                                selected = isCatalogLanguageSelected(selected, code),
                                onClick = { chooseLanguage(code) },
                            )
                        }
                    }
                }
                if (search.unavailable != null) {
                    val name = TranslationTargetPreference.localizedName(search.unavailable, displayLocale)
                    PreferenceHubSection(modifier = Modifier.padding(top = PreferenceHubSectionGap)) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(Res.string.settings_language_default_translation_app_title))
                            },
                            supportingContent = {
                                Text(stringResource(Res.string.settings_language_search_unavailable, name))
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable {
                                store.setUseDefaultTranslationApp(true)
                                onBack()
                            },
                        )
                    }
                }
                PreferenceHubFooter(stringResource(Res.string.settings_language_translate_into_list_footer))
            }
        }
    }
}

@Composable
private fun LanguageCodeRow(code: String, displayLocale: String, selected: Boolean, onClick: () -> Unit) {
    val name = TranslationTargetPreference.displayName(forLanguageCode = code, inLocale = displayLocale)
    LanguageChoiceRow(
        title = name.autonym,
        subtitle = name.subtitle,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun LanguageChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    )
}

private fun isCatalogLanguageSelected(selected: TranslationTargetPreference, code: String): Boolean =
    !selected.isMatchAppLanguage &&
        !selected.isSystemOverlay &&
        MessageLanguageDetector.isSameLanguage(selected.rawValue, code)
