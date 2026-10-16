package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.RegionFloodSpokenLabel
import one.meshcore.shared.remotenode.RegionNameValidator
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.remotenode.WILDCARD_REGION_NAME
import one.meshcore.shared.remotenode.parentPickerNamedLabel
import one.meshcore.shared.remotenode.regionFloodSpokenLabel
import one.meshcore.shared.remotenode.regionMutationsAllowed
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import one.meshcore.shared.resources.localizable_common_delete
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_child
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_region
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_region_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_all_traffic
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_all_traffic_wildcard
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_allow_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_child_of
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_default_scope
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_default_scope_caption
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_duplicate
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_empty
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_flood_toggle_caption
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_flood_toggle_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_invalid_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_load_default_scope
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_name_too_long
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_no_default
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_parent
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_region_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_save_to_device
import one.meshcore.shared.settings.ui.RadioOptionPicker
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NodeRegionsSection(isRadioReady: Boolean, state: NodeSettingsState, viewModel: NodeSettingsViewModel) {
    SettingsDisclosure(
        expanded = state.regionsExpanded,
        onExpandedChange = viewModel::setRegionsExpanded,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_regions),
        isLoading = state.isLoadingRegions,
        onReload = viewModel::loadRegions,
        footer = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = state.isLoadingRegions,
            loaded = state.regionsLoaded,
            sectionError = state.regionsSectionError,
            onReload = viewModel::loadRegions,
        ) {
            val mutationsEnabled = regionMutationsAllowed(
                regionsLoaded = state.regionsLoaded,
                isLoadingRegions = state.isLoadingRegions,
                isSavingRegions = state.isSavingRegions,
            )
            if (state.regions.isEmpty()) {
                PreferenceHubStatusRow(
                    stringResource(Res.string.remotenodes_remote_nodes_settings_regions_empty),
                )
            } else {
                if (state.supportsRegionDefaultScope) {
                    DefaultScopeRow(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
                }
                state.regions.forEach { entry ->
                    RegionRow(
                        isRadioReady = isRadioReady,
                        entry = entry,
                        hasChildren = !entry.isUnscoped &&
                            state.regions.any { it.parentName == entry.name },
                        mutationsEnabled = mutationsEnabled,
                        onToggleFlood = { viewModel.toggleRegionFlood(entry.name) },
                        onAddChild = { viewModel.beginAddChild(entry) },
                        onRemove = { viewModel.removeRegion(entry.name) },
                    )
                }
                PreferenceHubStatusRow(
                    stringResource(Res.string.remotenodes_remote_nodes_settings_regions_flood_toggle_caption),
                )
            }
            NodeRegionAddForm(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                mutationsEnabled = mutationsEnabled,
            )
        }
    }
}

@Composable
private fun NodeRegionAddForm(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
    mutationsEnabled: Boolean,
) {
    Text(
        text = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_add_region_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = PreferenceHubCardPadding,
            end = PreferenceHubCardPadding,
            top = SETTINGS_FIELD_SPACING,
        ),
    )
    val unscopedSpoken = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_all_traffic)
    val unscopedDisplay = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_all_traffic_wildcard)
    val parentOptions = listOf(RepeaterRegionEntry.Parent.Unscoped) +
        state.regions.filter { !it.isUnscoped }.map { RepeaterRegionEntry.Parent.Named(it.name) }
    RadioOptionPicker(
        label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_parent),
        selectedLabel = parentPickerNamedLabel(state.newRegionParent) ?: unscopedDisplay,
        selectedAccessibilityLabel = parentPickerNamedLabel(state.newRegionParent) ?: unscopedSpoken,
        accessibilityHint = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_parent),
        options = parentOptions,
        optionLabel = { parent -> parentPickerNamedLabel(parent) ?: unscopedDisplay },
        optionAccessibilityLabel = { parent -> parentPickerNamedLabel(parent) ?: unscopedSpoken },
        enabled = mutationsEnabled,
        onSelect = viewModel::setNewRegionParent,
    )
    val existingRegionNames = state.regions.map { it.name }
    val regionNameError = RegionNameValidator.inlineError(state.newRegionName, existingRegionNames)
    SettingsDisclosureFields {
        AppOutlinedTextField(
            value = state.newRegionName,
            onValueChange = viewModel::setNewRegionName,
            label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_regions_region_name)) },
            singleLine = true,
            isError = regionNameError != null,
            supportingText = regionNameValidationText(regionNameError),
            modifier = Modifier.fillMaxWidth(),
        )
        ApplyButton(
            isRadioReady = isRadioReady,
            label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_add_region),
            enabled = RegionNameValidator.isValid(state.newRegionName, existingRegionNames) && mutationsEnabled,
            isApplying = false,
            onClick = viewModel::addRegion,
        )
        ApplyButton(
            isRadioReady = isRadioReady,
            label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_save_to_device),
            enabled = state.hasUnsavedRegionChanges && !state.isSavingRegions,
            isApplying = state.isSavingRegions,
            showSuccess = state.regionsSaveSuccess,
            onClick = viewModel::saveRegions,
        )
    }
}

@Composable
private fun DefaultScopeRow(isRadioReady: Boolean, state: NodeSettingsState, viewModel: NodeSettingsViewModel) {
    val noneLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_no_default)
    if (state.defaultScopeLoaded) {
        val names = state.regions.filter { !it.isUnscoped }.map { it.name }.toMutableList()
        val current = state.defaultScopeName
        if (current != null && current != WILDCARD_REGION_NAME && current !in names) {
            names.add(current)
        }
        val options: List<String?> = listOf(null) + names
        RadioOptionPicker(
            label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_default_scope),
            selectedLabel = current ?: noneLabel,
            selectedAccessibilityLabel = current ?: noneLabel,
            accessibilityHint = if (isRadioReady) {
                stringResource(Res.string.remotenodes_remote_nodes_settings_regions_default_scope_caption)
            } else {
                stringResource(Res.string.localizable_accessibility_requires_radio_connection)
            },
            options = options,
            optionLabel = { it ?: noneLabel },
            optionAccessibilityLabel = { it ?: noneLabel },
            enabled = isRadioReady &&
                regionMutationsAllowed(
                    regionsLoaded = state.regionsLoaded,
                    isLoadingRegions = state.isLoadingRegions,
                    isSavingRegions = state.isSavingRegions,
                ),
            onSelect = viewModel::setDefaultScope,
        )
    } else if (state.isLoadingDefaultScope) {
        LoadingRow()
    } else {
        ApplyButton(
            isRadioReady = isRadioReady,
            label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_load_default_scope),
            enabled = regionMutationsAllowed(
                regionsLoaded = state.regionsLoaded,
                isLoadingRegions = state.isLoadingRegions,
                isSavingRegions = state.isSavingRegions,
            ),
            isApplying = false,
            onClick = viewModel::loadDefaultScope,
        )
    }
    PreferenceHubStatusRow(
        stringResource(Res.string.remotenodes_remote_nodes_settings_regions_default_scope_caption),
    )
}

@Composable
private fun RegionRow(
    isRadioReady: Boolean,
    entry: RepeaterRegionEntry,
    hasChildren: Boolean,
    mutationsEnabled: Boolean,
    onToggleFlood: () -> Unit,
    onAddChild: () -> Unit,
    onRemove: () -> Unit,
) {
    val indentPerLevelDp = maxOf(1, (REGION_INDENT_PER_LEVEL_DP * LocalDensity.current.fontScale).toInt())
    val visibleDepth = visibleRegionDepth(entry.depth, indentPerLevelDp)
    val regionName = if (entry.isUnscoped) {
        stringResource(Res.string.remotenodes_remote_nodes_settings_regions_all_traffic_wildcard)
    } else {
        entry.name
    }
    val unscopedSpoken = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_all_traffic)
    val spoken = when (val label = regionFloodSpokenLabel(entry)) {
        RegionFloodSpokenLabel.Unscoped -> unscopedSpoken
        is RegionFloodSpokenLabel.NameOnly -> label.name
        is RegionFloodSpokenLabel.ChildOfNamed ->
            formatTemplate(
                stringResource(Res.string.remotenodes_remote_nodes_settings_regions_child_of),
                listOf(label.name, label.parent),
            )
        is RegionFloodSpokenLabel.ChildOfUnscoped ->
            formatTemplate(
                stringResource(Res.string.remotenodes_remote_nodes_settings_regions_child_of),
                listOf(label.name, unscopedSpoken),
            )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListItem(
            headlineContent = {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (visibleDepth > 0) {
                        RegionGutter(depth = visibleDepth, indentPerLevelDp = indentPerLevelDp)
                    }
                    Text(
                        text = regionName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (!entry.isUnscoped && hasChildren) FontWeight.Medium else FontWeight.Normal,
                        color = if (entry.isUnscoped) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                }
            },
            trailingContent = if (!entry.isUnscoped) {
                {
                    val removeLabel = stringResource(Res.string.localizable_common_delete)
                    AppIconButton(
                        imageVector = AppIcons.Delete,
                        contentDescription = removeLabel,
                        onClick = onRemove,
                        enabled = isRadioReady && mutationsEnabled,
                        modifier = Modifier.radioConnectionHint(isRadioReady),
                        tint = if (isRadioReady && mutationsEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = DISABLED_REGION_ACTION_ALPHA)
                        },
                    )
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsToggleRow(
            label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_allow_flood),
            checked = entry.floodAllowed,
            onCheckedChange = { onToggleFlood() },
            enabled = mutationsEnabled,
            isRadioReady = isRadioReady,
            accessibilityHint = stringResource(
                Res.string.remotenodes_remote_nodes_settings_regions_flood_toggle_hint,
            ),
        )
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.remotenodes_remote_nodes_settings_regions_add_child))
            },
            leadingContent = {
                Icon(
                    imageVector = REGION_ADD_CHILD_ICON,
                    contentDescription = null,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().clickable(
                enabled = mutationsEnabled,
                onClick = onAddChild,
            ),
        )
    }
}

/**
 * Live add-region field errors. Empty is not shown: Add stays disabled, matching iOS
 * `RepeaterAddRegionSheet.validationErrorText`.
 */
@Composable
private fun regionNameValidationText(error: RegionNameValidator.ValidationError?): (@Composable () -> Unit)? {
    val message = when (error) {
        RegionNameValidator.ValidationError.InvalidCharacters ->
            stringResource(Res.string.remotenodes_remote_nodes_settings_regions_invalid_name)
        is RegionNameValidator.ValidationError.TooLong ->
            formattedStringResource(
                Res.string.remotenodes_remote_nodes_settings_regions_name_too_long,
                error.maxBytes,
            )
        RegionNameValidator.ValidationError.Duplicate ->
            stringResource(Res.string.remotenodes_remote_nodes_settings_regions_duplicate)
        RegionNameValidator.ValidationError.Empty, null -> return null
    }
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}

/** Marks the home region beside its name; the spoken label says the same in words. */
private const val REGION_INDENT_PER_LEVEL_DP = 12
private const val REGION_INDENT_MAX_GUTTER_DP = 48
private const val REGION_MAX_VISUAL_INDENT_DEPTH = 4
private const val REGION_SPINE_WIDTH_DP = 2
private const val DISABLED_REGION_ACTION_ALPHA = 0.38f

internal fun visibleRegionDepth(regionDepth: Int, indentPerLevelDp: Int = REGION_INDENT_PER_LEVEL_DP): Int {
    if (regionDepth <= 0) return 0
    val depthCap = minOf(regionDepth, REGION_MAX_VISUAL_INDENT_DEPTH)
    val widthCap = maxOf(1, REGION_INDENT_MAX_GUTTER_DP / indentPerLevelDp)
    return minOf(depthCap, widthCap)
}

@Composable
private fun RegionGutter(depth: Int, indentPerLevelDp: Int) {
    Row(modifier = Modifier.fillMaxHeight()) {
        repeat(depth) {
            Box(
                modifier = Modifier
                    .width(indentPerLevelDp.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .width(REGION_SPINE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}
