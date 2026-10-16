package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_maps_appearance
import one.meshcore.shared.resources.settings_maps_appearance_footer
import one.meshcore.shared.resources.settings_maps_offline_header
import one.meshcore.shared.resources.settings_offline_maps_title
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubRadioRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Maps: a basemap appearance radio group and an Offline Maps entry. Mirrors iOS
 * `MapsSettingsView`. Discovered-pin visibility lives on the map Filter FAB (`MapFilterState`), not here.
 * Appearance styles map tiles only, so it reuses the system/light/dark labels but persists through
 * [MapDisplayPreferencesStore], independent of app-chrome Appearance.
 */
@Composable
fun MapsSettingsScreen(
    store: MapDisplayPreferencesStore,
    onOpenOfflineMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme by store.mapColorScheme.collectAsStateWithLifecycle()

    Column(modifier.preferenceHubScroll()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_maps_appearance),
            contentModifier = Modifier.selectableGroup(),
        ) {
            colorSchemeChoices().forEach { (pref, label) ->
                PreferenceHubRadioRow(
                    label = label,
                    selected = scheme == pref,
                    onSelect = { store.setMapColorScheme(pref) },
                )
            }
        }
        PreferenceHubFooter(stringResource(Res.string.settings_maps_appearance_footer))

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_maps_offline_header),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_offline_maps_title)) },
                leadingContent = {
                    Icon(
                        imageVector = AppIcons.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenOfflineMaps),
            )
        }
    }
}
