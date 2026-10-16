package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.pathedit.AddHopIntent
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.clearError
import one.meshcore.shared.pathedit.initializeEditablePath
import one.meshcore.shared.pathedit.loadContacts
import one.meshcore.shared.pathedit.moveRepeater
import one.meshcore.shared.pathedit.removeRepeater
import one.meshcore.shared.pathedit.resetPath
import one.meshcore.shared.pathedit.saveEditedPath
import one.meshcore.shared.pathedit.setDirectRouting
import one.meshcore.shared.pathedit.setInsertionIntent
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_common_save
import one.meshcore.shared.resources.contacts_path_edit_current_path
import one.meshcore.shared.resources.contacts_path_edit_description
import one.meshcore.shared.resources.contacts_path_edit_direct_routing_confirm_confirm
import one.meshcore.shared.resources.contacts_path_edit_direct_routing_confirm_message
import one.meshcore.shared.resources.contacts_path_edit_direct_routing_confirm_title
import one.meshcore.shared.resources.contacts_path_edit_flood_routing_confirm_confirm
import one.meshcore.shared.resources.contacts_path_edit_flood_routing_confirm_message
import one.meshcore.shared.resources.contacts_path_edit_flood_routing_confirm_title
import one.meshcore.shared.resources.contacts_path_edit_title
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The contact path-editing sheet, ported from the iOS `PathEditingSheet`. Shows the ordered hops with
 * reorder/remove controls, the Add-Hop CTA, and the empty-state routing actions (Direct/Flood). The
 * Add-Hop picker is presented in place when `insertionIntent` is set, with a guaranteed back control
 * (the iOS split-view sheet workaround does not apply on Android — §10.6).
 *
 * The host opens this after loading contacts and initializing the editable path; both run here on entry.
 * Nested Up dismisses, matching Channel Info — iOS Cancel on a sheet is the wrong Android control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathEditingSheet(
    viewModel: PathManagementViewModel,
    contact: ContactDto,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var showDirectConfirm by remember { mutableStateOf(false) }
    var showFloodConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(
        viewModel,
        contact.id,
        contact.radioID,
        contact.publicKey.contentHashCode(),
        contact.lastModified,
    ) {
        viewModel.loadContacts(contact.radioID)
        viewModel.initializeEditablePath(contact)
    }

    // Add-Hop is a local-state overlay inside this sheet (not a NavKey). Register a nested system-back
    // handler so Back clears the picker and returns to the hop list instead of dismissing Edit Path.
    if (state.insertionIntent != null) {
        val addHopBackState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = addHopBackState,
            isBackEnabled = true,
            onBackCompleted = { viewModel.setInsertionIntent(null) },
        )
        AddHopPickerScreen(source = viewModel, onBack = { viewModel.setInsertionIntent(null) }, modifier = modifier)
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_path_edit_title),
                onBack = onDismiss,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = formattedStringResource(Res.string.contacts_path_edit_description, contact.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(PathEditMetrics.rowInset),
            )
            if (state.editablePath.isEmpty()) {
                PathEditingEmptyState(
                    contactName = contact.displayName,
                    onAddHop = { viewModel.setInsertionIntent(AddHopIntent.APPEND) },
                    onDirect = { showDirectConfirm = true },
                    onFlood = { showFloodConfirm = true },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        PreferenceHubSection(
                            title = stringResource(Res.string.contacts_path_edit_current_path),
                        ) {
                            state.editablePath.forEachIndexed { index, hop ->
                                PathEditingHopRow(
                                    hop = hop,
                                    index = index,
                                    total = state.editablePath.size,
                                    onMoveUp = {
                                        haptics.tap()
                                        viewModel.moveRepeater(index, index - 1)
                                    },
                                    onMoveDown = {
                                        haptics.tap()
                                        viewModel.moveRepeater(index, index + 2)
                                    },
                                    onRemove = {
                                        haptics.tap()
                                        viewModel.removeRepeater(index)
                                    },
                                )
                            }
                        }
                    }
                }
                PathEditingAddHopCta(
                    isPathFull = viewModel.isPathFull(),
                    maxHopCount = viewModel.maxHopCount(),
                    onAddHop = { viewModel.setInsertionIntent(AddHopIntent.APPEND) },
                )
                Button(
                    onClick = {
                        scope.launch {
                            if (viewModel.saveEditedPath(contact)) {
                                haptics.success()
                                onDismiss()
                            }
                        }
                    },
                    enabled = !state.isSettingPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PathEditMetrics.rowInset),
                ) {
                    Text(stringResource(Res.string.contacts_common_save))
                }
            }
        }
    }

    state.errorMessage?.let { error ->
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_common_error),
            message = error.text(),
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = { viewModel.clearError() },
        )
    }

    if (showDirectConfirm) {
        PathEditingRoutingConfirmDialog(
            title = stringResource(Res.string.contacts_path_edit_direct_routing_confirm_title),
            message = formattedStringResource(
                Res.string.contacts_path_edit_direct_routing_confirm_message,
                contact.displayName,
                contact.displayName,
            ),
            confirmLabel = stringResource(Res.string.contacts_path_edit_direct_routing_confirm_confirm),
            onConfirm = {
                showDirectConfirm = false
                scope.launch {
                    if (viewModel.setDirectRouting(contact)) {
                        haptics.selection()
                        onDismiss()
                    }
                }
            },
            onDismiss = { showDirectConfirm = false },
        )
    }

    if (showFloodConfirm) {
        PathEditingRoutingConfirmDialog(
            title = stringResource(Res.string.contacts_path_edit_flood_routing_confirm_title),
            message = formattedStringResource(
                Res.string.contacts_path_edit_flood_routing_confirm_message,
                contact.displayName,
            ),
            confirmLabel = stringResource(Res.string.contacts_path_edit_flood_routing_confirm_confirm),
            onConfirm = {
                showFloodConfirm = false
                scope.launch {
                    if (viewModel.resetPath(contact)) {
                        haptics.selection()
                        onDismiss()
                    }
                }
            },
            onDismiss = { showFloodConfirm = false },
        )
    }
}
