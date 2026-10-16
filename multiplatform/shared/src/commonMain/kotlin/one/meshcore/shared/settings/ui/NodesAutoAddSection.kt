package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.contact.AutoAddMode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_advanced_radio_apply
import one.meshcore.shared.resources.settings_nodes_auto_add_contacts
import one.meshcore.shared.resources.settings_nodes_auto_add_mode
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_all
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_all_description
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_manual
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_manual_description
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_selected_types
import one.meshcore.shared.resources.settings_nodes_auto_add_mode_selected_types_description
import one.meshcore.shared.resources.settings_nodes_auto_add_repeaters
import one.meshcore.shared.resources.settings_nodes_auto_add_room_servers
import one.meshcore.shared.resources.settings_nodes_auto_add_types_header
import one.meshcore.shared.resources.settings_nodes_max_hops
import one.meshcore.shared.resources.settings_nodes_max_hops_direct_only
import one.meshcore.shared.resources.settings_nodes_max_hops_footer_active
import one.meshcore.shared.resources.settings_nodes_max_hops_hops
import one.meshcore.shared.resources.settings_nodes_max_hops_no_limit
import one.meshcore.shared.resources.settings_nodes_max_hops_one_hop
import one.meshcore.shared.resources.settings_nodes_overwrite_oldest
import one.meshcore.shared.resources.settings_nodes_overwrite_oldest_description
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.advancedSettingsCanApply
import one.meshcore.shared.settings.settingsModified
import one.meshcore.shared.settings.toDraft
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubRadioRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/** Additional hop-count rows beyond "No Limit"/"Direct Only"/"1 Hop", mirroring iOS's `2...6` picker range. */
private val MAX_HOPS_ADDITIONAL_RANGE = 2..6

/** Mode, type, and hop toggles mirror the iOS nodes settings matrix. */
@Composable
internal fun NodesAutoAddSection(
    snapshot: NodesAutoAddSnapshot,
    isDeviceConnected: Boolean,
    onApply: suspend (NodesAutoAddDraft) -> Unit,
    onMaxRetriesExceeded: () -> Unit,
) {
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    val initial = remember(snapshot) { snapshot.toDraft() }
    var draft by remember(snapshot) { mutableStateOf(initial) }
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var appliedOk by remember { mutableStateOf(false) }
    val retryAlert = rememberRetryAlertState()

    fun applyDraft(value: NodesAutoAddDraft) {
        launchNodesAutoAdd(
            scope,
            NodesAutoAddApply(
                draft = value,
                onApply = onApply,
                onApplying = { isApplying = it },
                onError = { errorMessage = it },
                onApplied = { appliedOk = it },
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    val modeDescription = when (draft.mode) {
        AutoAddMode.MANUAL -> stringResource(Res.string.settings_nodes_auto_add_mode_manual_description)
        AutoAddMode.SELECTED_TYPES ->
            stringResource(Res.string.settings_nodes_auto_add_mode_selected_types_description)
        AutoAddMode.ALL -> stringResource(Res.string.settings_nodes_auto_add_mode_all_description)
    }

    PreferenceHubSection(
        title = stringResource(Res.string.settings_nodes_auto_add_mode),
        applyHorizontalPadding = false,
        modifier = Modifier.padding(top = 24.dp),
        contentModifier = Modifier.selectableGroup(),
    ) {
        val autoAddModes = if (snapshot.supportsAutoAddConfig) {
            listOf(AutoAddMode.MANUAL, AutoAddMode.SELECTED_TYPES, AutoAddMode.ALL)
        } else {
            listOf(AutoAddMode.MANUAL, AutoAddMode.ALL)
        }
        autoAddModes.forEach { mode ->
            PreferenceHubRadioRow(
                label = autoAddModeLabel(mode),
                selected = draft.mode == mode,
                enabled = !isApplying,
                onSelect = {
                    draft = draft.copy(mode = mode)
                    appliedOk = false
                },
            )
        }
    }
    PreferenceHubFooter(
        modeDescription,
        applyHorizontalPadding = false,
    )

    NodesAutoAddTypeToggles(
        snapshot = snapshot,
        draft = draft,
        isApplying = isApplying,
        onDraftChange = { updated ->
            draft = updated
            appliedOk = false
        },
    )

    NodesAutoAddMaxHops(
        snapshot = snapshot,
        draft = draft,
        isApplying = isApplying,
        onDraftChange = { updated ->
            draft = updated
            appliedOk = false
        },
    )

    if (snapshot.supportsAutoAddConfig) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_nodes_overwrite_oldest),
                supporting = stringResource(Res.string.settings_nodes_overwrite_oldest_description),
                checked = draft.overwriteOldest,
                enabled = !isApplying,
                onCheckedChange = {
                    draft = draft.copy(overwriteOldest = it)
                    appliedOk = false
                },
            )
        }
    }

    Button(
        onClick = { applyDraft(draft) },
        enabled = advancedSettingsCanApply(
            isDeviceConnected = isDeviceConnected,
            settingsModified = snapshot.settingsModified(draft),
            isApplying = isApplying,
            showSuccess = appliedOk,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .radioConnectionHint(isDeviceConnected),
    ) {
        AdvancedApplyButtonLabel(
            isApplying = isApplying,
            label = stringResource(Res.string.settings_advanced_radio_apply),
        )
    }

    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

@Composable
private fun NodesAutoAddTypeToggles(
    snapshot: NodesAutoAddSnapshot,
    draft: NodesAutoAddDraft,
    isApplying: Boolean,
    onDraftChange: (NodesAutoAddDraft) -> Unit,
) {
    if (snapshot.supportsAutoAddConfig && draft.mode == AutoAddMode.SELECTED_TYPES) {
        PreferenceHubSection(
            modifier = Modifier.padding(top = 8.dp),
            title = stringResource(Res.string.settings_nodes_auto_add_types_header),
            applyHorizontalPadding = false,
        ) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_nodes_auto_add_contacts),
                checked = draft.autoAddContacts,
                enabled = !isApplying,
                onCheckedChange = { onDraftChange(draft.copy(autoAddContacts = it)) },
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_nodes_auto_add_repeaters),
                checked = draft.autoAddRepeaters,
                enabled = !isApplying,
                onCheckedChange = { onDraftChange(draft.copy(autoAddRepeaters = it)) },
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_nodes_auto_add_room_servers),
                checked = draft.autoAddRoomServers,
                enabled = !isApplying,
                onCheckedChange = { onDraftChange(draft.copy(autoAddRoomServers = it)) },
            )
        }
    }
}

@Composable
private fun NodesAutoAddMaxHops(
    snapshot: NodesAutoAddSnapshot,
    draft: NodesAutoAddDraft,
    isApplying: Boolean,
    onDraftChange: (NodesAutoAddDraft) -> Unit,
) {
    if (snapshot.supportsAutoAddMaxHops && draft.mode != AutoAddMode.MANUAL) {
        PreferenceHubSection(
            modifier = Modifier.padding(top = 8.dp),
            contentModifier = Modifier.selectableGroup(),
            title = stringResource(Res.string.settings_nodes_max_hops),
            applyHorizontalPadding = false,
        ) {
            PreferenceHubRadioRow(
                label = stringResource(Res.string.settings_nodes_max_hops_no_limit),
                selected = draft.autoAddMaxHops == 0,
                enabled = !isApplying,
                onSelect = { onDraftChange(draft.copy(autoAddMaxHops = 0)) },
            )
            PreferenceHubRadioRow(
                label = stringResource(Res.string.settings_nodes_max_hops_direct_only),
                selected = draft.autoAddMaxHops == 1,
                enabled = !isApplying,
                onSelect = { onDraftChange(draft.copy(autoAddMaxHops = 1)) },
            )
            PreferenceHubRadioRow(
                label = stringResource(Res.string.settings_nodes_max_hops_one_hop),
                selected = draft.autoAddMaxHops == 2,
                enabled = !isApplying,
                onSelect = { onDraftChange(draft.copy(autoAddMaxHops = 2)) },
            )
            for (hops in MAX_HOPS_ADDITIONAL_RANGE) {
                val tag = hops + 1
                PreferenceHubRadioRow(
                    label = formattedStringResource(Res.string.settings_nodes_max_hops_hops, hops),
                    selected = draft.autoAddMaxHops == tag,
                    enabled = !isApplying,
                    onSelect = { onDraftChange(draft.copy(autoAddMaxHops = tag)) },
                )
            }
        }
        if (draft.autoAddMaxHops > 0) {
            PreferenceHubFooter(
                stringResource(Res.string.settings_nodes_max_hops_footer_active),
                applyHorizontalPadding = false,
            )
        }
    }
}

@Composable
private fun autoAddModeLabel(mode: AutoAddMode): String = when (mode) {
    AutoAddMode.MANUAL -> stringResource(Res.string.settings_nodes_auto_add_mode_manual)
    AutoAddMode.SELECTED_TYPES -> stringResource(Res.string.settings_nodes_auto_add_mode_selected_types)
    AutoAddMode.ALL -> stringResource(Res.string.settings_nodes_auto_add_mode_all)
}
