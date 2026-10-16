package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.MapStyleDisabledReason
import one.meshcore.shared.map.MapStyleGate
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.mapsAppearancePreference
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_controls_cluster_nodes
import one.meshcore.shared.resources.map_controls_lock_north
import one.meshcore.shared.resources.map_controls_map_options
import one.meshcore.shared.resources.map_controls_show_labels
import one.meshcore.shared.resources.map_style_accessibility_label
import one.meshcore.shared.resources.map_style_no_offline_coverage
import one.meshcore.shared.resources.map_style_requires_network
import one.meshcore.shared.resources.map_style_satellite
import one.meshcore.shared.resources.map_style_standard
import one.meshcore.shared.resources.map_style_topo
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuSectionHeader
import org.jetbrains.compose.resources.stringResource

/**
 * Live map-options values shared by every iOS `MapControlsToolbar` host: style, pin labels, north
 * lock, and Settings → Maps appearance. Reads [store] when present so Settings and the Map tab stay
 * in sync; otherwise the persisted style from [preferences], system appearance, and the label/north
 * defaults.
 */
@Composable
fun rememberMapDisplayChrome(
    store: MapDisplayPreferencesStore?,
    preferences: PreferencesStore? = null,
): MapDisplayChrome {
    val savedStyle = rememberSavedMapStyle(preferences)
    val style = store?.mapStyle?.collectAsStateWithLifecycle()?.value ?: savedStyle
    val showLabels = store?.mapShowLabels?.collectAsStateWithLifecycle()?.value
        ?: PersistenceKeys.DEFAULT_MAP_SHOW_LABELS
    val clusteringEnabled = store?.mapClusteringEnabled?.collectAsStateWithLifecycle()?.value
        ?: PersistenceKeys.DEFAULT_MAP_CLUSTERING_ENABLED
    val northLocked = store?.mapNorthLocked?.collectAsStateWithLifecycle()?.value
        ?: PersistenceKeys.DEFAULT_MAP_NORTH_LOCKED
    val colorScheme = mapsAppearancePreference(
        store?.mapColorScheme?.collectAsStateWithLifecycle()?.value ?: LocalMapColorSchemePreference.current,
    )
    return MapDisplayChrome(
        style = style,
        showLabels = showLabels,
        clusteringEnabled = clusteringEnabled,
        northLocked = northLocked,
        colorScheme = colorScheme,
        setStyle = { next -> store?.setMapStyle(next) },
        toggleLabels = { store?.setMapShowLabels(!showLabels) },
        toggleClustering = { store?.setMapClusteringEnabled(!clusteringEnabled) },
        toggleNorthLock = { store?.setMapNorthLocked(!northLocked) },
    )
}

/**
 * Layers FAB plus style / labels / clustering / north-lock menu. Mirrors the map-options half of iOS
 * `MapControlsToolbar`. Style gating stays with the host: it depends on network and offline-pack coverage,
 * which [MapDisplayChrome] does not know.
 */
@Composable
fun MapOptionsMenu(
    chrome: MapDisplayChrome,
    styleDisabled: (MapStyleSelection) -> Boolean,
    styleDisabledReason: (MapStyleSelection) -> String?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        SmallFloatingActionButton(
            onClick = { menuOpen = true },
            containerColor = FloatingActionButtonDefaults.containerColor,
        ) {
            Icon(
                imageVector = AppIcons.Layers,
                contentDescription = stringResource(Res.string.map_style_accessibility_label),
            )
        }
        McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            McMenuSectionHeader(text = stringResource(Res.string.map_controls_map_options))
            MapStyleSelection.entries.forEach { style ->
                val reason = styleDisabledReason(style)
                McMenuItem(
                    text = mapStyleSelectionLabel(style),
                    leadingIcon = AppIcons.Layers,
                    selected = style == chrome.style,
                    enabled = !styleDisabled(style),
                    modifier = if (reason != null) {
                        Modifier.semantics { stateDescription = reason }
                    } else {
                        Modifier
                    },
                    onClick = {
                        menuOpen = false
                        chrome.setStyle(style)
                    },
                )
            }
            HorizontalDivider()
            McMenuItem(
                text = stringResource(Res.string.map_controls_show_labels),
                selected = chrome.showLabels,
                onClick = { chrome.toggleLabels() },
            )
            McMenuItem(
                text = stringResource(Res.string.map_controls_cluster_nodes),
                selected = chrome.clusteringEnabled,
                onClick = { chrome.toggleClustering() },
            )
            McMenuItem(
                text = stringResource(Res.string.map_controls_lock_north),
                selected = chrome.northLocked,
                onClick = { chrome.toggleNorthLock() },
            )
        }
    }
}

@Composable
fun mapStyleSelectionLabel(style: MapStyleSelection): String = when (style) {
    MapStyleSelection.STANDARD -> stringResource(Res.string.map_style_standard)
    MapStyleSelection.SATELLITE -> stringResource(Res.string.map_style_satellite)
    MapStyleSelection.TOPO -> stringResource(Res.string.map_style_topo)
}

/**
 * Style-picker gating when the caller has no pack-coverage query: satellite stays off while the
 * device is offline; standard and topo stay selectable. Maps that know their viewport should use
 * [MapStyleGate] with [one.meshcore.shared.map.OfflineMapPackRegistry] instead.
 */
@Composable
fun rememberNetworkOnlyStyleGate(
    isNetworkAvailable: Boolean,
): Pair<
    (MapStyleSelection) -> Boolean,
    (MapStyleSelection) -> String?,
    > {
    val requiresNetworkHint = stringResource(Res.string.map_style_requires_network)
    val noOfflineCoverageHint = stringResource(Res.string.map_style_no_offline_coverage)
    return remember(isNetworkAvailable, requiresNetworkHint, noOfflineCoverageHint) {
        val disabled: (MapStyleSelection) -> Boolean = { style ->
            MapStyleGate.isDisabled(style, isNetworkAvailable, hasOfflineCoverage = true)
        }
        val reason: (MapStyleSelection) -> String? = { style ->
            when (MapStyleGate.disabledReason(style, isNetworkAvailable, hasOfflineCoverage = true)) {
                MapStyleDisabledReason.REQUIRES_NETWORK -> requiresNetworkHint
                MapStyleDisabledReason.NO_OFFLINE_COVERAGE -> noOfflineCoverageHint
                null -> null
            }
        }
        disabled to reason
    }
}
