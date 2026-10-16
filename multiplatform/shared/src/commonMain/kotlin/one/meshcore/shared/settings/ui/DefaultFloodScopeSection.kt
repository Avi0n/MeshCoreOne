package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region_discover
import one.meshcore.shared.resources.chats_channel_info_region_manage_regions
import one.meshcore.shared.resources.settings_default_flood_scope_disabled
import one.meshcore.shared.resources.settings_default_flood_scope_footer
import one.meshcore.shared.resources.settings_default_flood_scope_header
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced → Default Flood Scope: picks the device's persisted default flood scope
 * (firmware v11+) from its known regions, discovers new ones, and links to Manage Regions. Mirrors
 * iOS `DefaultFloodScopeSection`.
 */
@Composable
fun DefaultFloodScopeSection(
    knownRegions: List<String>,
    currentScopeName: String?,
    enabled: Boolean,
    onApply: suspend (String?) -> Unit,
    onDiscover: suspend () -> RegionDiscoverUiResult,
    discoverMessage: suspend (RegionDiscoverUiResult) -> String?,
    onManageRegions: () -> Unit,
    /** After the last retry, leave Advanced (iOS `dismiss()`). */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settingsErrorText = rememberSettingsErrorText()
    val retryAlert = rememberRetryAlertState()
    val scope = rememberCoroutineScope()
    var isApplying by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val sortedRegions = remember(knownRegions) { knownRegions.sorted() }

    fun apply(name: String?) {
        launchFloodScopeApply(
            scope,
            FloodScopeApply(
                name = name,
                inFlight = isApplying,
                onApply = onApply,
                onInFlight = { isApplying = it },
                onStatus = { statusMessage = it },
                onError = { errorMessage = it },
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_default_flood_scope_header),
            applyHorizontalPadding = false,
        ) {
            ScopeRow(
                title = stringResource(Res.string.settings_default_flood_scope_disabled),
                selected = currentScopeName == null,
                enabled = enabled && !isApplying,
                isRadioReady = enabled,
                onClick = { apply(null) },
            )
            for (region in sortedRegions) {
                ScopeRow(
                    title = region,
                    selected = currentScopeName == region,
                    enabled = enabled && !isApplying,
                    isRadioReady = enabled,
                    onClick = { apply(region) },
                )
            }

            RegionDiscoverStatusRow(isDiscovering = isDiscovering, statusMessage = statusMessage)

            ListItem(
                headlineContent = { Text(stringResource(Res.string.chats_channel_info_region_discover)) },
                leadingContent = { Icon(FLOOD_SCOPE_DISCOVER_ICON, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .radioConnectionHint(enabled)
                    .clickable(enabled = enabled && !isDiscovering) {
                        isDiscovering = true
                        statusMessage = null
                        scope.launch {
                            try {
                                statusMessage = discoverMessage(onDiscover())
                            } finally {
                                isDiscovering = false
                            }
                        }
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.chats_channel_info_region_manage_regions)) },
                leadingContent = { Icon(FLOOD_SCOPE_MANAGE_ICON, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .radioConnectionHint(enabled)
                    .clickable(enabled = enabled, onClick = onManageRegions),
            )
        }

        PreferenceHubFooter(
            stringResource(Res.string.settings_default_flood_scope_footer),
            applyHorizontalPadding = false,
        )
    }
    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

@Composable
private fun ScopeRow(title: String, selected: Boolean, enabled: Boolean, isRadioReady: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .radioConnectionHint(isRadioReady)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    )
}

/** iOS default-flood-scope discover `antenna.radiowaves.left.and.right`. */
internal val FLOOD_SCOPE_DISCOVER_ICON get() = AppIcons.CellTower

/** iOS default-flood-scope manage-regions `list.bullet`. */
internal val FLOOD_SCOPE_MANAGE_ICON get() = AppIcons.ListAlt
