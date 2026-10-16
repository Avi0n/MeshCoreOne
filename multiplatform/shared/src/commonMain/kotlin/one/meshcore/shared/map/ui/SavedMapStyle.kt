package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.mapStyleFromRaw
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * The Map-tab style persisted under [PersistenceKeys.MAP_STYLE_SELECTION]. Secondary maps (LOS,
 * Trace Path, remote-node location) read this so they match iOS `@AppStorage` rather than always
 * rendering Standard.
 */
@Composable
fun rememberSavedMapStyle(preferences: PreferencesStore?): MapStyleSelection {
    var style by remember { mutableStateOf(MapStyleSelection.STANDARD) }
    LaunchedEffect(preferences) {
        val prefs = preferences ?: return@LaunchedEffect
        style = mapStyleFromRaw(prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
    }
    return style
}
