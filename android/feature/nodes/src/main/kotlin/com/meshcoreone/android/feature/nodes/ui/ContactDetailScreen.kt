// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/PathEditing/PathEditingSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.RemoteNodeAction
import com.meshcoreone.android.feature.nodes.detail.ContactDetailPresentation
import com.meshcoreone.android.feature.nodes.detail.ContactDetailStateHolder
import com.meshcoreone.android.feature.nodes.detail.DetailAction
import com.meshcoreone.android.feature.nodes.detail.PingResult
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.path.AddHopIntent
import com.meshcoreone.android.feature.nodes.path.PathDiscoveryResult
import com.meshcoreone.android.feature.nodes.path.PathManagementStateHolder
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import com.meshcoreone.android.feature.nodes.share.ContactQrShareStateHolder
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
internal fun ContactDetailScreen(
    initialContact: ContactDTO,
    dependencies: NodesFeatureDependencies,
    navigation: NodesNavigation,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(initialContact.id, dependencies) {
        ContactDetailStateHolder(initialContact, dependencies, scope)
    }
    val state by holder.state.collectAsStateWithLifecycle()
    val pathState by holder.path.state.collectAsStateWithLifecycle()
    val contact = state.contact
    var showShare by remember { mutableStateOf(false) }
    var showPathEditor by remember { mutableStateOf(false) }
    var resetPathConfirmation by remember { mutableStateOf(false) }
    var camera by remember(contact.id) {
        mutableStateOf(
            if (contact.hasLocation) MapCamera(GeoPoint(contact.latitude, contact.longitude), 0.04, 0.04) else null,
        )
    }

    LaunchedEffect(holder) { holder.run() }
    DisposableEffect(holder) { onDispose(holder::onDisappear) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(contact.type.localizedNameRes()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    contact.publicKey.uppercaseHexString(" "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(C.contactsDetailInfo), style = MaterialTheme.typography.titleMedium)
                    if (state.isEditingNickname) {
                        OutlinedTextField(
                            value = state.nickname,
                            onValueChange = holder::setNickname,
                            label = { Text(stringResource(C.contactsDetailNickname)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { holder.saveNickname() } }, enabled = !state.isSaving) {
                                Text(stringResource(C.contactsCommonSave))
                            }
                            TextButton(onClick = holder::cancelEditingNickname) { Text(stringResource(C.contactsCommonCancel)) }
                        }
                    } else {
                        DetailValue(stringResource(C.contactsDetailName), contact.name)
                        DetailValue(stringResource(C.contactsDetailNickname), contact.nickname ?: stringResource(C.contactsDetailNicknameNone))
                        TextButton(onClick = holder::beginEditingNickname) { Text(stringResource(C.contactsCommonEdit)) }
                    }
                    DetailValue(stringResource(C.contactsDetailType), stringResource(contact.type.localizedNameRes()))
                    if (ContactDetailPresentation.showsUnreadCount(contact)) {
                        DetailValue(stringResource(C.contactsDetailUnreadMessages), contact.unreadCount.toString())
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(C.contactsDetailTechnical), style = MaterialTheme.typography.titleMedium)
                    val pathDisplay = ContactDetailPresentation.pathDisplayWithNames(contact, holder.path::resolveHashToName)
                    DetailValue(stringResource(C.contactsDetailRoute), nodesString(ContactDetailPresentation.routeDisplayText(contact, pathDisplay)))
                    if (ContactDetailPresentation.showsHopsAway(contact)) {
                        DetailValue(stringResource(C.contactsDetailHopsAway), contact.pathHopCount.toString())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { holder.path.discoverPath(contact) },
                            enabled = radioReady(dependencies) && !pathState.isDiscovering,
                        ) {
                            Text(
                                pathState.discoverySecondsRemaining?.let {
                                    stringResource(R.string.l10n_app_contacts_contacts_detail_secondsremaining, it)
                                } ?: stringResource(C.contactsDetailDiscoverPath),
                            )
                        }
                        Button(
                            onClick = {
                                holder.path.initializeEditablePath(contact)
                                showPathEditor = true
                            },
                            enabled = radioReady(dependencies) && !pathState.isSettingPath,
                        ) { Text(stringResource(C.contactsDetailEditPath)) }
                        TextButton(
                            onClick = { resetPathConfirmation = true },
                            enabled = radioReady(dependencies) &&
                                !ContactDetailPresentation.resetPathDisabled(contact, pathState.isSettingPath),
                        ) { Text(stringResource(C.contactsDetailResetPath)) }
                    }
                    if (ContactDetailPresentation.isRoutePopulated(contact)) {
                        val context = LocalContext.current
                        val routeLabel = stringResource(C.contactsDetailRoute)
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    routeLabel,
                                    ContactDetailPresentation.routeIdPrefixes(contact),
                                ),
                            )
                        }) { Text(stringResource(C.contactsDetailCopyRoute)) }
                    }
                }
            }
        }
        if (contact.hasLocation) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            stringResource(C.contactsDetailLocation),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                        val point = GeoPoint(contact.latitude, contact.longitude)
                        if (MapLibreRuntime.isSupported) {
                            MapLibreMapSurface(
                                presentation = MapPresentationState(
                                    availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
                                    style = MapStyle.STANDARD,
                                    camera = camera,
                                    points = listOf(
                                        MapMarker(
                                            contact.id,
                                            point,
                                            when (contact.type) {
                                                ContactType.CHAT -> MapPinStyle.CONTACT_CHAT
                                                ContactType.REPEATER -> MapPinStyle.CONTACT_REPEATER
                                                ContactType.ROOM -> MapPinStyle.CONTACT_ROOM
                                            },
                                            contact.displayName,
                                            false,
                                        ),
                                    ),
                                ),
                                clusteringEnabled = false,
                                labelsEnabled = true,
                                accessibilityLabel = stringResource(C.contactsDetailLocation),
                                onCameraChanged = { camera = it },
                                onMarkerSelected = {},
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        }
                        DetailValue(
                            stringResource(C.contactsDetailCoordinates),
                            "${contact.latitude}, ${contact.longitude}",
                            Modifier.padding(horizontal = 16.dp),
                        )
                        TextButton(
                            onClick = { navigation.openMap(contact.latitude, contact.longitude) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(C.contactsDetailOpenInMaps)) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(C.contactsDetailShareContact), style = MaterialTheme.typography.titleMedium)
                    ContactDetailPresentation.actions(contact, holder.showFromDirectChat).forEach { action ->
                        DetailActionButton(
                            action = action,
                            state = state,
                            radioEnabled = radioReady(dependencies),
                            onClick = {
                                when (action) {
                                    DetailAction.SEND_MESSAGE -> navigation.openChat(contact)
                                    DetailAction.SHARE_QR -> showShare = true
                                    DetailAction.SHARE_VIA_ADVERT -> scope.launch { holder.shareViaAdvert() }
                                    DetailAction.FAVORITE -> holder.setFavorite(!state.isFavorite)
                                    DetailAction.PING -> scope.launch { holder.pingRepeater() }
                                    DetailAction.MANAGEMENT -> navigation.openRemoteNode(contact, RemoteNodeAction.MANAGEMENT)
                                    DetailAction.TELEMETRY -> navigation.openRemoteNode(contact, RemoteNodeAction.TELEMETRY)
                                    DetailAction.SAVED_HISTORY -> navigation.openRemoteNode(contact, RemoteNodeAction.SAVED_HISTORY)
                                    DetailAction.JOIN_ROOM -> navigation.openRemoteNode(contact, RemoteNodeAction.JOIN_ROOM)
                                }
                            },
                        )
                    }
                    state.pingResult?.let { result ->
                        Text(
                            when (result) {
                                is PingResult.Success -> stringResource(
                                    R.string.l10n_app_contacts_contacts_detail_pingsuccesslabel,
                                    result.latencyMs,
                                    result.snrThere.toInt(),
                                    result.snrBack.toInt(),
                                )
                                is PingResult.Error -> nodesString(result.message)
                            },
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(C.contactsDetailDangerZone), style = MaterialTheme.typography.titleMedium)
                    if (ContactDetailPresentation.showsBlockAndClear(contact)) {
                        Button(
                            onClick = { scope.launch { holder.requestToggleBlock() } },
                            enabled = radioReady(dependencies),
                        ) {
                            Text(
                                stringResource(
                                    if (contact.isBlocked) C.contactsDetailUnblockContact else C.contactsDetailBlockContact,
                                ),
                            )
                        }
                        Button(onClick = holder::requestClearMessages) { Text(stringResource(C.contactsDetailClearMessages)) }
                    }
                    if (ContactDetailPresentation.showsDelete(holder.isVContact)) {
                        Button(onClick = holder::requestDelete, enabled = radioReady(dependencies)) {
                            Text(stringResource(R.string.l10n_app_contacts_contacts_detail_deletetype, stringResource(contact.type.localizedNameRes())))
                        }
                    }
                }
            }
        }
    }

    state.pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = holder::cancelConfirmation,
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (holder.confirm()) navigation.back()
                    }
                }) { Text(stringResource(C.contactsCommonOk)) }
            },
            dismissButton = {
                TextButton(onClick = holder::cancelConfirmation) { Text(stringResource(C.contactsCommonCancel)) }
            },
            title = { Text(nodesString(confirmation.title)) },
            text = { Text(nodesString(confirmation.message)) },
        )
    }
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = holder::clearError,
            confirmButton = { TextButton(onClick = holder::clearError) { Text(stringResource(C.contactsCommonOk)) } },
            title = { Text(stringResource(C.contactsCommonError)) },
            text = { Text(nodesString(error)) },
        )
    }
    pathState.discoveryResult?.takeIf { pathState.showDiscoveryResult }?.let { result ->
        AlertDialog(
            onDismissRequest = holder.path::dismissDiscoveryResult,
            confirmButton = {
                TextButton(onClick = holder.path::dismissDiscoveryResult) { Text(stringResource(C.contactsCommonOk)) }
            },
            title = { Text(stringResource(C.contactsDetailAlertPathDiscovery)) },
            text = { Text(nodesString(result.description)) },
        )
    }
    if (resetPathConfirmation) {
        AlertDialog(
            onDismissRequest = { resetPathConfirmation = false },
            confirmButton = {
                TextButton(onClick = {
                    resetPathConfirmation = false
                    scope.launch { holder.path.resetPath(contact) }
                }) { Text(stringResource(C.contactsDetailResetPath)) }
            },
            dismissButton = {
                TextButton(onClick = { resetPathConfirmation = false }) { Text(stringResource(C.contactsCommonCancel)) }
            },
            title = { Text(stringResource(C.contactsDetailResetPath)) },
            text = { Text(stringResource(C.contactsDetailFloodFooter)) },
        )
    }
    if (showPathEditor) {
        PathEditorDialog(contact, holder.path, onDismiss = { showPathEditor = false })
    }
    if (showShare) {
        ContactShareDialog(
            remember(contact.id) {
                ContactQrShareStateHolder(contact.displayName, contact.publicKey, contact.type, dependencies, scope)
            },
            onDismiss = { showShare = false },
        )
    }
}

@Composable
private fun DetailValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun DetailActionButton(
    action: DetailAction,
    state: com.meshcoreone.android.feature.nodes.detail.ContactDetailState,
    radioEnabled: Boolean,
    onClick: () -> Unit,
) {
    val label = when (action) {
        DetailAction.JOIN_ROOM -> C.contactsDetailJoinRoom
        DetailAction.SEND_MESSAGE -> C.contactsDetailSendMessage
        DetailAction.TELEMETRY -> C.contactsDetailTelemetry
        DetailAction.SAVED_HISTORY -> C.contactsDetailSavedHistory
        DetailAction.MANAGEMENT -> C.contactsDetailManagement
        DetailAction.PING -> C.contactsDetailPing
        DetailAction.SHARE_QR -> C.contactsDetailShareContact
        DetailAction.SHARE_VIA_ADVERT -> C.contactsDetailShareViaAdvert
        DetailAction.FAVORITE -> if (state.isFavorite) C.contactsDetailRemoveFromFavorites else C.contactsDetailAddToFavorites
    }
    val needsRadio = action in setOf(DetailAction.JOIN_ROOM, DetailAction.SEND_MESSAGE, DetailAction.PING, DetailAction.SHARE_VIA_ADVERT, DetailAction.MANAGEMENT, DetailAction.TELEMETRY)
    Button(
        onClick = onClick,
        enabled = (!needsRadio || radioEnabled) && !(action == DetailAction.PING && state.isPinging) &&
            !(action == DetailAction.SHARE_VIA_ADVERT && state.isSharing),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if ((action == DetailAction.PING && state.isPinging) || (action == DetailAction.SHARE_VIA_ADVERT && state.isSharing)) {
            CircularProgressIndicator(Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp))
        } else {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun PathEditorDialog(
    contact: ContactDTO,
    holder: PathManagementStateHolder,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by holder.state.collectAsStateWithLifecycle()
    var showNodes by remember { mutableStateOf(false) }
    var codes by remember { mutableStateOf("") }
    var codeFeedback by remember { mutableStateOf<com.meshcoreone.android.feature.nodes.deps.NodesMessage?>(null) }
    var routingConfirmation by remember { mutableStateOf<PathDiscoveryResult?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(C.contactsDetailEditPath), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(state.editablePath, key = { it.id }) { hop ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(hop.displayText, Modifier.weight(1f))
                            TextButton(onClick = {
                                holder.moveRepeater(setOf(state.editablePath.indexOf(hop)), (state.editablePath.indexOf(hop) - 1).coerceAtLeast(0))
                            }) { Text("↑") }
                            TextButton(onClick = {
                                holder.moveRepeater(setOf(state.editablePath.indexOf(hop)), (state.editablePath.indexOf(hop) + 2).coerceAtMost(state.editablePath.size))
                            }) { Text("↓") }
                            TextButton(onClick = { holder.removeRepeater(state.editablePath.indexOf(hop)) }) {
                                Text(stringResource(C.contactsCommonDelete))
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Button(
                    onClick = { showNodes = true },
                    enabled = !holder.isPathFull,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_addhop)) }
                OutlinedTextField(
                    value = codes,
                    onValueChange = { codes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(C.contactsPathEditSearchPrompt)) },
                    supportingText = { codeFeedback?.let { Text(nodesString(it), color = MaterialTheme.colorScheme.error) } },
                )
                TextButton(
                    onClick = {
                        val result = holder.addCodes(codes)
                        codeFeedback = result.errorMessage
                        if (!result.hasErrors) codes = ""
                    },
                    enabled = codes.isNotBlank() && !holder.isPathFull,
                ) { Text(stringResource(C.contactsPathEditPaste)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { routingConfirmation = PathDiscoveryResult.Success(0) }) {
                        Text(stringResource(C.contactsRouteDirect))
                    }
                    TextButton(onClick = { routingConfirmation = PathDiscoveryResult.NoPathFound }) {
                        Text(stringResource(C.contactsRouteFlood))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(C.contactsCommonCancel)) }
                    Button(
                        onClick = { scope.launch { if (holder.saveFromEditor(contact)) onDismiss() } },
                        enabled = !state.isSettingPath,
                    ) { Text(stringResource(C.contactsCommonSave)) }
                }
                state.errorMessage?.let { Text(nodesString(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (showNodes) {
        val candidates = remember(state.availableRepeaters, state.availableRooms, state.discoveredNodes) {
            buildList<RepeaterResolvable> {
                addAll(state.availableRepeaters)
                addAll(state.availableRooms)
                addAll(state.discoveredNodes)
            }
        }
        AlertDialog(
            onDismissRequest = { showNodes = false },
            confirmButton = { TextButton(onClick = { showNodes = false }) { Text(stringResource(C.contactsCommonDone)) } },
            title = { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_addhop)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.publicKey.hexString }) { node ->
                        TextButton(
                            onClick = {
                                holder.insert(node, AddHopIntent.APPEND)
                                if (holder.isPathFull) showNodes = false
                            },
                            enabled = !holder.isPathFull,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(node.resolvableName) }
                    }
                }
            },
        )
    }
    routingConfirmation?.let { route ->
        AlertDialog(
            onDismissRequest = { routingConfirmation = null },
            confirmButton = {
                TextButton(onClick = {
                    routingConfirmation = null
                    scope.launch {
                        val completed = when (route) {
                            is PathDiscoveryResult.Success -> holder.confirmDirectRouting(contact)
                            else -> holder.confirmFloodRouting(contact)
                        }
                        if (completed) onDismiss()
                    }
                }) { Text(stringResource(C.contactsCommonOk)) }
            },
            dismissButton = {
                TextButton(onClick = { routingConfirmation = null }) { Text(stringResource(C.contactsCommonCancel)) }
            },
            title = { Text(stringResource(C.contactsDetailEditPath)) },
            text = { Text(nodesString(route.description)) },
        )
    }
}

private fun radioReady(dependencies: NodesFeatureDependencies): Boolean =
    dependencies.session.connectionState() == DeviceConnectionState.READY
