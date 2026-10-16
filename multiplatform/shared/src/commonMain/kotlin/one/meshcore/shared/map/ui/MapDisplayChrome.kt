package one.meshcore.shared.map.ui

import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Live map-options values shared by every iOS `MapControlsToolbar` host: style, pin labels, north
 * lock, and Settings → Maps appearance.
 */
data class MapDisplayChrome(
    val style: MapStyleSelection,
    val showLabels: Boolean,
    val clusteringEnabled: Boolean,
    val northLocked: Boolean,
    val colorScheme: AppColorSchemePreference,
    val setStyle: (MapStyleSelection) -> Unit,
    val toggleLabels: () -> Unit,
    val toggleClustering: () -> Unit,
    val toggleNorthLock: () -> Unit,
)
