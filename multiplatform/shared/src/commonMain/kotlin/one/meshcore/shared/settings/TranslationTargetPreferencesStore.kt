package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.translation.TranslationTargetPreference
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore

data class TranslationTargetPreferences(
    val languageRawValue: String = PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
    val useDefaultTranslationApp: Boolean = PersistenceKeys.DEFAULT_USE_DEFAULT_TRANSLATION_APP,
    val translationOffersEnabled: Boolean = PersistenceKeys.DEFAULT_TRANSLATION_OFFERS_ENABLED,
) {
    val usesSystemOverlay: Boolean
        get() = TranslationTargetPreference.usesSystemOverlay(languageRawValue, useDefaultTranslationApp)

    fun resolvedLanguageCode(appLocaleIdentifier: String): String =
        TranslationTargetPreference(rawValue = languageRawValue).resolvedLanguageCode(appLocaleIdentifier)
}

class TranslationTargetPreferencesStore(private val preferences: PreferencesStore, private val scope: CoroutineScope) {
    private val loadGuard = PreferenceLoadGuard<Field>()
    private val _state = MutableStateFlow(TranslationTargetPreferences())
    val state: StateFlow<TranslationTargetPreferences> = _state.asStateFlow()

    fun load() {
        val loadSnapshot = loadGuard.beginLoad()
        scope.launch {
            val loaded = preferences.loadTranslationTargetPreferences()
            val current = _state.value
            _state.value = TranslationTargetPreferences(
                languageRawValue = loaded.languageRawValue.takeIf {
                    loadGuard.isCurrent(loadSnapshot, Field.LANGUAGE)
                } ?: current.languageRawValue,
                useDefaultTranslationApp = loaded.useDefaultTranslationApp.takeIf {
                    loadGuard.isCurrent(loadSnapshot, Field.DEFAULT_APP)
                } ?: current.useDefaultTranslationApp,
                translationOffersEnabled = loaded.translationOffersEnabled.takeIf {
                    loadGuard.isCurrent(loadSnapshot, Field.OFFERS)
                } ?: current.translationOffersEnabled,
            )
        }
    }

    fun setLanguageRawValue(value: String) {
        loadGuard.recordMutation(Field.LANGUAGE, Field.DEFAULT_APP)
        _state.value = _state.value.copy(
            languageRawValue = value,
            useDefaultTranslationApp = false,
        )
        scope.launch {
            preferences.putString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE, value)
            preferences.putBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP, false)
        }
    }

    fun setUseDefaultTranslationApp(value: Boolean) {
        loadGuard.recordMutation(Field.DEFAULT_APP, Field.LANGUAGE)
        val current = _state.value
        val nextLanguage = if (!value &&
            TranslationTargetPreference(current.languageRawValue).isSystemOverlay
        ) {
            TranslationTargetPreference.MATCH_APP_LANGUAGE
        } else {
            current.languageRawValue
        }
        _state.value = current.copy(
            useDefaultTranslationApp = value,
            languageRawValue = nextLanguage,
        )
        scope.launch {
            preferences.putBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP, value)
            if (nextLanguage != current.languageRawValue) {
                preferences.putString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE, nextLanguage)
            }
        }
    }

    fun setTranslationOffersEnabled(value: Boolean) {
        loadGuard.recordMutation(Field.OFFERS)
        _state.value = _state.value.copy(translationOffersEnabled = value)
        scope.launch { preferences.putBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED, value) }
    }

    /**
     * `"overlay"` as the language is a legacy sentinel: turn the default-app toggle on and store
     * `"app"` so the language subtag is not lost to the sentinel, matching iOS Language settings appear.
     */
    fun adoptOverlaySentinel() {
        val current = _state.value
        if (!TranslationTargetPreference(current.languageRawValue).isSystemOverlay) return
        loadGuard.recordMutation(Field.DEFAULT_APP, Field.LANGUAGE)
        _state.value = current.copy(
            useDefaultTranslationApp = true,
            languageRawValue = TranslationTargetPreference.MATCH_APP_LANGUAGE,
        )
        scope.launch {
            preferences.putBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP, true)
            preferences.putString(
                PersistenceKeys.TRANSLATION_TARGET_LANGUAGE,
                TranslationTargetPreference.MATCH_APP_LANGUAGE,
            )
        }
    }

    private enum class Field { LANGUAGE, DEFAULT_APP, OFFERS }
}

suspend fun PreferencesStore.loadTranslationTargetPreferences(): TranslationTargetPreferences =
    TranslationTargetPreferences(
        languageRawValue = getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE)
            ?: PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
        useDefaultTranslationApp = getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP)
            ?: PersistenceKeys.DEFAULT_USE_DEFAULT_TRANSLATION_APP,
        translationOffersEnabled = getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED)
            ?: PersistenceKeys.DEFAULT_TRANSLATION_OFFERS_ENABLED,
    )
