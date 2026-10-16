package one.meshcore.shared.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Live appearance toggles for Settings → Appearance and [one.meshcore.shared.ui.AppRoot] theme
 * application. Mirrors the preference half of Swift `ThemeService` (named theme palettes land later).
 */
class AppearancePreferencesStore(private val preferences: PreferencesStore, private val scope: CoroutineScope) {
    private val loadGuard = PreferenceLoadGuard<Field>()
    private val _colorScheme = MutableStateFlow(AppColorSchemePreference.SYSTEM)
    val colorScheme: StateFlow<AppColorSchemePreference> = _colorScheme.asStateFlow()

    private val _selectedThemeId = MutableStateFlow(PersistenceKeys.DEFAULT_THEME_ID)
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    fun load() {
        val loadSnapshot = loadGuard.beginLoad()
        scope.launch {
            val schemeRaw = preferences.getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE)
            val selectedThemeId = preferences.getString(PersistenceKeys.SELECTED_THEME_ID)
                ?: PersistenceKeys.DEFAULT_THEME_ID
            if (loadGuard.isCurrent(loadSnapshot, Field.COLOR_SCHEME)) {
                _colorScheme.value = AppColorSchemePreference.fromRaw(schemeRaw)
            }
            if (loadGuard.isCurrent(loadSnapshot, Field.THEME)) _selectedThemeId.value = selectedThemeId
        }
    }

    fun setColorScheme(preference: AppColorSchemePreference) {
        loadGuard.recordMutation(Field.COLOR_SCHEME)
        _colorScheme.value = preference
        scope.launch {
            preferences.putString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE, preference.rawValue)
        }
    }

    fun setSelectedThemeId(id: String) {
        loadGuard.recordMutation(Field.THEME)
        _selectedThemeId.value = id
        scope.launch {
            preferences.putString(PersistenceKeys.SELECTED_THEME_ID, id)
        }
    }

    private enum class Field { COLOR_SCHEME, THEME }
}
