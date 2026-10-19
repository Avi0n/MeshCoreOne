// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterAddRegionSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RegionFloodToggleRow.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.feature.remotenodes.settings.*
import kotlinx.coroutines.launch

@Composable
internal fun RemoteRegionsContent(
    holder: RepeaterRegionsStateHolder,
    enabled: Boolean,
    confirm: (Int, suspend () -> Unit) -> Unit,
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var add by remember { mutableStateOf<AddRegionForm?>(null) }
    RemoteSection(
        L.remoteNodesSettingsRegions, state.isRegionsExpanded,
        { holder.setRegionsExpanded(!state.isRegionsExpanded); if (!state.regionsLoaded && enabled) scope.launch { holder.fetchRegions() } },
        state.isLoadingRegions, state.regionsError, enabled, { scope.launch { holder.fetchRegions() } },
    ) {
        state.regions.forEach { region ->
            Column(Modifier.padding(start = (RegionFloodToggleRowLayout.visibleDepth(region.depth, 12.0) * 12).dp)) {
                RemoteValue(stringResource(L.remoteNodesSettingsRegionsRegionName), remoteText(RegionFloodToggleRowLayout.displayName(region)))
                if (region.isHome) Text(stringResource(L.remoteNodesSettingsRegionsHomeRegion))
                RemoteToggle(
                    L.remoteNodesSettingsRegionsFloodToggleCaption, region.floodAllowed,
                    { confirm(L.remoteNodesSettingsRegionsFloodToggleCaption) { holder.toggleRegionFlood(region.name) } },
                    enabled, androidx.compose.ui.semantics.Role.Switch,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ add = AddRegionForm(selectedParent = if (region.isUnscoped) RepeaterRegionEntry.Parent.Unscoped else RepeaterRegionEntry.Parent.Named(region.name)) }, enabled = enabled) {
                        Text(stringResource(L.remoteNodesSettingsRegionsAddChild))
                    }
                    if (!region.isUnscoped) TextButton(
                        { confirm(AppLocalizableStrings.commonDelete) { holder.removeRegion(region.name) } }, enabled = enabled,
                    ) { Text(stringResource(AppLocalizableStrings.commonDelete)) }
                }
            }
        }
        if (holder.supportsRegionDefaultScope) {
            RemoteValue(stringResource(L.remoteNodesSettingsRegionsDefaultScope), state.defaultScopeName ?: stringResource(L.remoteNodesSettingsRegionsNoDefault))
            TextButton({ scope.launch { holder.fetchDefaultScope() } }, enabled = enabled && !state.isLoadingDefaultScope) {
                Text(stringResource(L.remoteNodesSettingsRegionsLoadDefaultScope))
            }
            if (state.defaultScopeLoaded) {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    TextButton({ choosing = true }, enabled = enabled) { Text(stringResource(L.remoteNodesSettingsRegionsDefaultScope)) }
                    DropdownMenu(choosing, { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(L.remoteNodesSettingsRegionsNoDefault)) },
                            onClick = { choosing = false; confirm(L.remoteNodesSettingsRegionsDefaultScope) { holder.setDefaultScope(null) } },
                        )
                        state.regions.filter { !it.isUnscoped }.forEach { region ->
                            DropdownMenuItem(
                                text = { Text(region.name) },
                                onClick = { choosing = false; confirm(L.remoteNodesSettingsRegionsDefaultScope) { holder.setDefaultScope(region.name) } },
                            )
                        }
                    }
                }
            }
        }
        RemoteApply(L.remoteNodesSettingsRegionsAddRegion, enabled && state.regionsLoaded) {
            add = AddRegionForm(selectedParent = RepeaterRegionEntry.Parent.Unscoped)
        }
        RemoteApply(L.remoteNodesSettingsRegionsSaveToDevice, enabled && state.hasUnsavedRegionChanges) {
            confirm(L.remoteNodesSettingsRegionsSaveToDevice, holder::saveRegions)
        }
        if (state.regionsSaveSuccess) Text(stringResource(L.remoteNodesSettingsSettingsApplied))
        Text(stringResource(L.remoteNodesSettingsRegionsFooter))
    }
    add?.let { form ->
        var choosing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { add = null },
            title = { Text(stringResource(L.remoteNodesSettingsRegionsAddRegionTitle)) },
            text = {
                Column {
                    OutlinedTextField(form.regionName, { add = form.editingName(it) }, label = { Text(stringResource(L.remoteNodesSettingsRegionsRegionName)) })
                    Box {
                        TextButton({ choosing = true }) { Text(form.selectedParent.id) }
                        DropdownMenu(choosing, { choosing = false }) {
                            AddRegionForm.parentOptions(state.regions).forEach { parent ->
                                DropdownMenuItem(
                                    text = { Text(parent.id) },
                                    onClick = { choosing = false; add = form.selectingParent(parent) },
                                )
                            }
                        }
                    }
                    RemoteFailure(form.displayedError(state.regions))
                }
            },
            dismissButton = { TextButton({ add = null }) { Text(stringResource(L.remoteNodesCancel)) } },
            confirmButton = {
                TextButton({
                    add = null
                    confirm(L.remoteNodesSettingsRegionsAddRegion) { holder.addRegion(form.trimmedName, form.selectedParent) }
                }, enabled = enabled && form.canAdd(state.regions)) { Text(stringResource(L.remoteNodesSettingsRegionsAddRegion)) }
            },
        )
    }
}
