package one.meshcore.shared.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Live map-display toggles for Settings → Maps and the Map tab. Mirrors the iOS `AppStorageKey`
 * keys for discovered nodes, basemap appearance, and `mapStyleSelection`. The basemap appearance is
 * deliberately separate from [one.meshcore.shared.theme.AppearancePreferencesStore]: it styles map tiles
 * only, not app chrome.
 */
class MapDisplayPreferencesStore(private val preferences: PreferencesStore, private val scope: CoroutineScope) {
    private val loadGuard = PreferenceLoadGuard<Field>()
    private val _showDiscoveredNodesOnMap =
        MutableStateFlow(PersistenceKeys.DEFAULT_SHOW_DISCOVERED_NODES_ON_MAP)
    val showDiscoveredNodesOnMap: StateFlow<Boolean> = _showDiscoveredNodesOnMap.asStateFlow()

    private val _mapColorScheme = MutableStateFlow(AppColorSchemePreference.SYSTEM)
    val mapColorScheme: StateFlow<AppColorSchemePreference> = _mapColorScheme.asStateFlow()

    private val _mapShowLabels = MutableStateFlow(PersistenceKeys.DEFAULT_MAP_SHOW_LABELS)
    val mapShowLabels: StateFlow<Boolean> = _mapShowLabels.asStateFlow()

    private val _mapClusteringEnabled = MutableStateFlow(PersistenceKeys.DEFAULT_MAP_CLUSTERING_ENABLED)
    val mapClusteringEnabled: StateFlow<Boolean> = _mapClusteringEnabled.asStateFlow()

    private val _mapNorthLocked = MutableStateFlow(PersistenceKeys.DEFAULT_MAP_NORTH_LOCKED)
    val mapNorthLocked: StateFlow<Boolean> = _mapNorthLocked.asStateFlow()

    private val _mapStyle = MutableStateFlow(MapStyleSelection.STANDARD)
    val mapStyle: StateFlow<MapStyleSelection> = _mapStyle.asStateFlow()

    fun load() {
        val loadSnapshot = loadGuard.beginLoad()
        scope.launch {
            val showDiscoveredNodes = preferences.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP)
                ?: PersistenceKeys.DEFAULT_SHOW_DISCOVERED_NODES_ON_MAP
            val mapColorScheme = AppColorSchemePreference.fromRaw(
                preferences.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE),
            )
            val mapShowLabels = preferences.getBoolean(PersistenceKeys.MAP_SHOW_LABELS)
                ?: PersistenceKeys.DEFAULT_MAP_SHOW_LABELS
            val mapClusteringEnabled = preferences.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED)
                ?: PersistenceKeys.DEFAULT_MAP_CLUSTERING_ENABLED
            val mapNorthLocked = preferences.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED)
                ?: PersistenceKeys.DEFAULT_MAP_NORTH_LOCKED
            val mapStyle = mapStyleFromRaw(preferences.getString(PersistenceKeys.MAP_STYLE_SELECTION))
            if (loadGuard.isCurrent(loadSnapshot, Field.DISCOVERED_NODES)) {
                _showDiscoveredNodesOnMap.value = showDiscoveredNodes
            }
            if (loadGuard.isCurrent(loadSnapshot, Field.COLOR_SCHEME)) _mapColorScheme.value = mapColorScheme
            if (loadGuard.isCurrent(loadSnapshot, Field.LABELS)) _mapShowLabels.value = mapShowLabels
            if (loadGuard.isCurrent(loadSnapshot, Field.CLUSTERING)) _mapClusteringEnabled.value = mapClusteringEnabled
            if (loadGuard.isCurrent(loadSnapshot, Field.NORTH_LOCK)) _mapNorthLocked.value = mapNorthLocked
            if (loadGuard.isCurrent(loadSnapshot, Field.STYLE)) _mapStyle.value = mapStyle
        }
    }

    fun setShowDiscoveredNodesOnMap(enabled: Boolean) {
        loadGuard.recordMutation(Field.DISCOVERED_NODES)
        _showDiscoveredNodesOnMap.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP, enabled)
        }
    }

    fun setMapColorScheme(preference: AppColorSchemePreference) {
        loadGuard.recordMutation(Field.COLOR_SCHEME)
        _mapColorScheme.value = preference
        scope.launch {
            preferences.putString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE, preference.rawValue)
        }
    }

    fun setMapShowLabels(enabled: Boolean) {
        loadGuard.recordMutation(Field.LABELS)
        _mapShowLabels.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.MAP_SHOW_LABELS, enabled)
        }
    }

    fun setMapClusteringEnabled(enabled: Boolean) {
        loadGuard.recordMutation(Field.CLUSTERING)
        _mapClusteringEnabled.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED, enabled)
        }
    }

    fun setMapNorthLocked(enabled: Boolean) {
        loadGuard.recordMutation(Field.NORTH_LOCK)
        _mapNorthLocked.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.MAP_NORTH_LOCKED, enabled)
        }
    }

    fun setMapStyle(style: MapStyleSelection) {
        loadGuard.recordMutation(Field.STYLE)
        _mapStyle.value = style
        scope.launch {
            preferences.putString(PersistenceKeys.MAP_STYLE_SELECTION, style.rawValue)
        }
    }

    private enum class Field { DISCOVERED_NODES, COLOR_SCHEME, LABELS, CLUSTERING, NORTH_LOCK, STYLE }
}
