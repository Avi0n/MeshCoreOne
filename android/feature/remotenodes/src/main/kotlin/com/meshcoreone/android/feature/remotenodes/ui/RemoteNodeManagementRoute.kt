// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import com.meshcoreone.android.feature.remotenodes.settings.*
import com.meshcoreone.android.feature.remotenodes.status.*
import kotlinx.coroutines.launch

typealias RemoteNodeCliContent = @Composable (RemoteNodeSessionDTO, RemoteCliSend, Boolean) -> Unit

private data class PendingAction(val title: Int, val action: suspend () -> Unit)

@Composable
internal fun RemoteNodeManagementRoute(
    session: RemoteNodeSessionDTO,
    dependencies: RemoteNodesFeatureDependencies?,
    catalog: RemoteNodesCatalog,
    ready: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAuthenticate: () -> Unit,
    cliContent: RemoteNodeCliContent? = null,
    initialHistory: Boolean = false,
    radioOptions: RemoteRadioOptions? = null,
    mapSurface: RemoteNodesMapSurface = NativeRemoteNodesMapSurface,
    telemetryOnly: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val repeaterSettings = remember(dependencies) {
        dependencies?.let { RepeaterSettingsStateHolder(it.clock, it.faults, scope) }
    }
    val roomSettings = remember(dependencies) {
        dependencies?.let { RoomSettingsStateHolder(it.clock, it.faults, scope) }
    }
    val repeaterStatus = remember(dependencies) {
        dependencies?.let { RepeaterStatusStateHolder(it.clock, it.faults, scope) }
    }
    val roomStatus = remember(dependencies) {
        dependencies?.let { RoomStatusStateHolder(it.clock, it.faults) }
    }
    val helper = if (session.isRepeater) repeaterSettings?.helper else roomSettings?.helper
    val status = if (session.isRepeater) repeaterStatus?.helper else roomStatus?.helper
    val historyStore = remember(dependencies) { dependencies?.historyStore() }
    var tab by rememberSaveable(session.id) {
        mutableStateOf(if (session.isAdmin && !telemetryOnly) NodeManagementTab.SETTINGS else NodeManagementTab.TELEMETRY)
    }
    var showHistory by rememberSaveable { mutableStateOf(initialHistory) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var routeError by remember { mutableStateOf<RemoteNodesText?>(null) }
    var showIdentity by remember { mutableStateOf(false) }
    val canWrite by rememberUpdatedState(ready && session.isAdmin)
    val connected by rememberUpdatedState(ready)
    var telemetryConfigured by remember { mutableStateOf(false) }

    LaunchedEffect(dependencies, session.id, ready) {
        if (ready && session.isAdmin && !telemetryOnly) {
            if (session.isRepeater) repeaterSettings?.configure({ dependencies?.repeaterAdmin() }, session)
            else roomSettings?.configure({ dependencies?.roomAdmin() }, session)
        }
    }
    LaunchedEffect(tab, dependencies) {
        if (tab == NodeManagementTab.TELEMETRY && !telemetryConfigured && dependencies != null) {
            status?.setSession(session)
            if (session.isRepeater) {
                repeaterStatus?.configure(dependencies)
                repeaterStatus?.registerHandlers()
            } else {
                roomStatus?.configure(dependencies)
                roomStatus?.registerHandlers()
            }
            telemetryConfigured = true
            status?.loadOCVSettings(session.publicKey, session.radioId)
        }
    }
    DisposableEffect(dependencies) {
        onDispose {
            repeaterStatus?.stopDiscovery()
            repeaterStatus?.clearStatusHandlers()
            roomStatus?.clearStatusHandlers()
            repeaterSettings?.cleanup()
            roomSettings?.cleanup()
        }
    }
    BackHandler(showHistory) { showHistory = false }
    val request: (Int, suspend () -> Unit) -> Unit = { title, action ->
        if (canWrite) pending = PendingAction(title, action)
        else routeError = RemoteNodesText.Resource(L.remoteNodesSettingsNoService)
    }
    Column(modifier.fillMaxHeight().padding(horizontal = 12.dp).testTag("remote-management")) {
        TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(L.remoteNodesDone)) }
        RemoteHeading(session.name)
        TextButton({ showIdentity = !showIdentity }) { Text(stringResource(L.remoteNodesRoomPublicKey)) }
        if (showIdentity) Text(session.publicKeyHex)
        Text(stringResource(
            if (session.isAdmin) L.remoteNodesPermissionAdmin
            else if (session.canPost) L.remoteNodesPermissionMember else L.remoteNodesPermissionGuest,
        ))
        if (!session.isAdmin) Text(stringResource(L.remoteNodesStatusGuestMode))
        RemoteFailure(routeError)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.isAdmin && !telemetryOnly) NodeManagementTab.entries.forEach { value ->
                FilterChip(
                    selected = !showHistory && tab == value,
                    onClick = { tab = value; showHistory = false },
                    label = { Text(stringResource(value.labelRes)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("remote-tab:${value.name}"),
                )
            }
            TextButton({ showHistory = true }, Modifier.heightIn(min = 48.dp).testTag("remote-history")) {
                Text(stringResource(L.remoteNodesHistoryOverviewTitle))
            }
            if (!ready) TextButton(onAuthenticate) { Text(stringResource(L.remoteNodesAuthAuthentication)) }
        }
        val cliSelected = !showHistory && tab == NodeManagementTab.CLI && helper != null
        // A visited CLI tab stays composed so its terminal state outlives tab changes.
        RetainedTabContent(cliSelected, Modifier.weight(1f)) {
            val send = if (session.isRepeater) repeaterSettings?.makeNodeCLISend(session) else roomSettings?.makeNodeCLISend(session)
            if (send != null && cliContent != null) cliContent(session, { _, command, timeout ->
                if (!canWrite) throw NodeSettingsNoServiceException()
                send(command, timeout)
            }, canWrite)
            else RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        }
        when {
            showHistory -> RemoteNodeHistoryRoute(
                session, historyStore, Modifier.weight(1f), mapSurface,
                dependencies?.clock ?: com.meshcoreone.android.feature.remotenodes.common.SystemRemoteNodesClock,
            )
            tab == NodeManagementTab.SETTINGS && helper != null -> RemoteNodeSettingsContent(
                helper, if (session.isRepeater) repeaterSettings else null,
                if (session.isRoom) roomSettings else null, canWrite, request, Modifier.weight(1f), radioOptions, mapSurface,
            )
            tab == NodeManagementTab.CLI && helper != null -> Unit
            status != null -> RemoteNodeStatusContent(
                session, status, if (session.isRepeater) repeaterStatus else null,
                if (session.isRoom) roomStatus else null, catalog, connected, Modifier.weight(1f), mapSurface,
            )
            else -> RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        }
    }
    pending?.let { action ->
        RemoteConfirmation(
            stringResource(action.title),
            stringResource(when (action.title) {
                L.remoteNodesSettingsRebootDevice -> L.remoteNodesSettingsRebootMessage
                L.remoteNodesSettingsApplyRadioSettings -> L.remoteNodesSettingsRadioRestartWarning
                else -> action.title
            }),
            onCancel = { pending = null },
        ) {
            pending = null
            scope.launch {
                if (!canWrite) routeError = RemoteNodesText.Resource(L.remoteNodesSettingsNoService)
                else try {
                    action.action()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    routeError = AddRegionForm.submitError(failure)
                }
            }
        }
    }
    if (helper != null) {
        val settings by helper.state.collectAsStateWithLifecycle()
        if (settings.showSuccessAlert) AlertDialog(
            onDismissRequest = helper::dismissSuccessAlert,
            title = { Text(stringResource(L.remoteNodesSettingsSuccess)) },
            text = { Text(remoteText(settings.successMessage ?: RemoteNodesText.Resource(L.remoteNodesSettingsSettingsApplied))) },
            confirmButton = { TextButton(helper::dismissSuccessAlert) { Text(stringResource(L.remoteNodesSettingsOk)) } },
        )
    }
}
