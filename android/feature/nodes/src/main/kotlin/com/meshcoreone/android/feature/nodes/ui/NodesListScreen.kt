// PortedFrom: MC1/Views/Contacts/ContactsSidebarContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactsListContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/AddContactSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactQRShareSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.add.AddContactStateHolder
import com.meshcoreone.android.feature.nodes.add.PublicKeyStatus
import com.meshcoreone.android.feature.nodes.add.ScanConfirmOutcome
import com.meshcoreone.android.feature.nodes.add.ScanContactStateHolder
import com.meshcoreone.android.feature.nodes.contacts.ContactRowActions
import com.meshcoreone.android.feature.nodes.contacts.ContactRowPresentation
import com.meshcoreone.android.feature.nodes.contacts.ContactsStateHolder
import com.meshcoreone.android.feature.nodes.contacts.RowActionEdge
import com.meshcoreone.android.feature.nodes.contacts.RowActionKind
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import com.meshcoreone.android.feature.nodes.share.ContactQrShareStateHolder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun NodesListScreen(
    dependencies: NodesFeatureDependencies,
    navigation: NodesNavigation,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { ContactsStateHolder(dependencies, scope) }
    val state by holder.state.collectAsStateWithLifecycle()
    val radioId = dependencies.session.currentRadioId()
    var search by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf(NodeSegment.CONTACTS) }
    var sort by remember { mutableStateOf(NodeSortOrder.LAST_HEARD) }
    var sortMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Pair<RowActionKind, ContactDTO>?>(null) }

    LaunchedEffect(holder, radioId) {
        if (radioId != null) holder.loadContacts(radioId)
    }
    LaunchedEffect(state.hasLoadedOnce) {
        if (state.hasLoadedOnce && segment == NodeSegment.CONTACTS && state.hasFavorites) segment = NodeSegment.FAVORITES
    }
    val contacts = remember(state.contacts, state.pendingRemovalIds, search, segment, sort) {
        holder.filteredContacts(search, segment, sort, null)
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            label = { Text(stringResource(C.contactsListSearchPrompt)) },
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NodeSegment.entries.forEach { item ->
                FilterChip(
                    selected = segment == item,
                    onClick = { segment = item },
                    label = { Text(stringResource(item.titleRes)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = navigation::openDiscovery) { Text(stringResource(C.contactsListDiscover)) }
            Button(onClick = { showAdd = true }) { Text(stringResource(C.contactsListAddContact)) }
            Button(
                onClick = { showShare = true },
                enabled = dependencies.session.connectedDevice() != null,
            ) { Text(stringResource(C.contactsListShareMyContact)) }
            Button(
                onClick = {
                    if (radioId != null) scope.launch {
                        if (dependencies.session.connectionState() == DeviceConnectionState.READY) holder.syncContacts(radioId)
                        else holder.seed {
                            it.copy(errorMessage = com.meshcoreone.android.feature.nodes.deps.NodesMessage.res(C.contactsListConnectToSync))
                        }
                    }
                },
                enabled = !state.isSyncing,
            ) {
                if (state.isSyncing) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp))
                else Text(stringResource(C.contactsListSyncNodes))
            }
            Box {
                Button(onClick = { sortMenu = true }) { Text(stringResource(C.contactsListSort)) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    NodeSortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(order.titleRes)) },
                            onClick = { sort = order; sortMenu = false },
                        )
                    }
                }
            }
        }
        when {
            !state.hasLoadedOnce || state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            contacts.isEmpty() -> NodesEmptyState(search, segment, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(contacts, key = { it.id }) { contact ->
                    ContactListRow(
                        contact = contact,
                        stateHolder = holder,
                        dependencies = dependencies,
                        onOpen = { navigation.openContact(contact) },
                        onAction = { pendingAction = it to contact },
                    )
                }
            }
        }
    }

    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = holder::clearError,
            confirmButton = { TextButton(onClick = holder::clearError) { Text(stringResource(C.contactsCommonOk)) } },
            title = { Text(stringResource(C.contactsCommonError)) },
            text = { Text(nodesString(error)) },
        )
    }
    pendingAction?.let { (action, contact) ->
        val destructive = action == RowActionKind.DELETE || action == RowActionKind.BLOCK
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    scope.launch {
                        when (action) {
                            RowActionKind.DELETE -> holder.deleteContact(contact)
                            RowActionKind.BLOCK, RowActionKind.UNBLOCK -> holder.toggleBlocked(contact)
                            RowActionKind.FAVORITE, RowActionKind.UNFAVORITE -> holder.toggleFavorite(contact)
                            RowActionKind.SEND_MESSAGE -> navigation.openChat(contact)
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (destructive) C.contactsCommonOk else C.contactsCommonDone,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text(stringResource(C.contactsCommonCancel)) }
            },
            title = { Text(contact.displayName) },
            text = {
                Text(
                    when (action) {
                        RowActionKind.DELETE -> stringResource(R.string.l10n_app_contacts_contacts_detail_alert_delete_message, contact.displayName)
                        RowActionKind.BLOCK -> stringResource(R.string.l10n_app_contacts_contacts_detail_alert_block_message, contact.displayName)
                        else -> contact.displayName
                    },
                )
            },
        )
    }
    if (showAdd) {
        AddContactDialog(
            dependencies = dependencies,
            navigation = navigation,
            onDismiss = { showAdd = false },
            onAdded = {
                showAdd = false
                if (radioId != null) scope.launch { holder.loadContacts(radioId) }
            },
        )
    }
    if (showShare) {
        dependencies.session.connectedDevice()?.let { device ->
            ContactShareDialog(
                holder = remember(device.id) {
                    ContactQrShareStateHolder(device.nodeName, device.publicKey, ContactType.CHAT, dependencies, scope)
                },
                onDismiss = { showShare = false },
            )
        } ?: run { showShare = false }
    }
}

@Composable
private fun ContactListRow(
    contact: ContactDTO,
    stateHolder: ContactsStateHolder,
    dependencies: NodesFeatureDependencies,
    onOpen: () -> Unit,
    onAction: (RowActionKind) -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val route = nodesString(ContactRowPresentation.routeLabel(contact, state.inboundHopByKey[contact.publicKey]))
    val prefix = ContactRowPresentation.idPrefixHex(contact, dependencies.session.connectedDevice()?.hashSize)
    val actions = ContactRowActions.actions(
        contact,
        RowActionEdge.ALL,
        dependencies.session.connectionState(),
        dependencies.session.connectedDevice()?.publicKey,
        state,
    )
    Surface(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(prefix, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(contact.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (contact.isFavorite) Text(" ★", color = MaterialTheme.colorScheme.tertiary)
                    if (contact.isBlocked) Text(" • ${stringResource(C.contactsDetailBlocked)}", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "${stringResource(contact.type.localizedNameRes())} • $route",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(contact.recencyDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(
                    onClick = { menu = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(nodesString(action.label)) },
                            enabled = action.enabled,
                            onClick = { menu = false; onAction(action.kind) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodesEmptyState(search: String, segment: NodeSegment, modifier: Modifier) {
    val (title, body) = if (search.isNotEmpty()) {
        stringResource(C.contactsListEmptySearchTitle) to
            stringResource(R.string.l10n_app_contacts_contacts_list_empty_search_description, search)
    } else when (segment) {
        NodeSegment.FAVORITES -> stringResource(C.contactsListEmptyFavoritesTitle) to stringResource(C.contactsListEmptyFavoritesDescription)
        NodeSegment.CONTACTS -> stringResource(C.contactsListEmptyContactsTitle) to stringResource(C.contactsListEmptyContactsDescription)
        NodeSegment.REPEATERS -> stringResource(C.contactsListEmptyRepeatersTitle) to stringResource(C.contactsListEmptyRepeatersDescription)
        NodeSegment.ROOMS -> stringResource(C.contactsListEmptyRoomsTitle) to stringResource(C.contactsListEmptyRoomsDescription)
    }
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddContactDialog(
    dependencies: NodesFeatureDependencies,
    navigation: NodesNavigation,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { AddContactStateHolder(dependencies) }
    val state by holder.state.collectAsStateWithLifecycle()
    var scanner by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.canAdd,
                onClick = { scope.launch { if (holder.add()) onAdded() } },
            ) { Text(stringResource(C.contactsAddAdd)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(C.contactsCommonCancel)) } },
        title = { Text(stringResource(C.contactsAddTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContactType.entries.forEach { type ->
                        FilterChip(
                            selected = state.selectedType == type,
                            onClick = { holder.selectType(type) },
                            label = { Text(stringResource(type.localizedNameRes())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.contactName,
                    onValueChange = holder::setContactName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(C.contactsAddContactName)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.publicKeyHex,
                    onValueChange = holder::setPublicKeyHex,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(C.contactsAddPublicKey)) },
                    supportingText = {
                        Text(
                            when (val status = state.publicKeyStatus) {
                                PublicKeyStatus.Empty -> stringResource(
                                    R.string.l10n_app_contacts_contacts_add_publickeyfooter,
                                    com.meshcoreone.android.feature.nodes.add.AddContactState.PUBLIC_KEY_HEX_LENGTH,
                                )
                                PublicKeyStatus.Valid -> stringResource(C.contactsAddValid)
                                is PublicKeyStatus.Count -> stringResource(
                                    R.string.l10n_app_contacts_contacts_add_charactercount,
                                    status.current,
                                    status.required,
                                )
                            },
                        )
                    },
                    singleLine = true,
                )
                val context = LocalContext.current
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                TextButton(onClick = {
                    holder.pasteContactUrl(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
                }) { Text(stringResource(C.contactsAddPasteURL)) }
                TextButton(onClick = { scanner = true }) { Text(stringResource(C.contactsAddScanQR)) }
                if (state.showPasteError) Text(stringResource(C.contactsAddErrorInvalidURL), color = MaterialTheme.colorScheme.error)
                state.errorMessage?.let { Text(nodesString(it), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
    if (scanner) {
        val scanHolder = remember(dependencies) { ScanContactStateHolder(dependencies, scope) }
        val scanState by scanHolder.state.collectAsStateWithLifecycle()
        androidx.compose.ui.window.Dialog(onDismissRequest = { scanner = false }) {
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 720.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                ContactQrScanner(scanHolder, onClose = { scanner = false })
            }
        }
        scanState.confirmation()?.let { confirmation ->
            AlertDialog(
                onDismissRequest = scanHolder::resetToScanner,
                confirmButton = {
                    TextButton(
                        enabled = confirmation.isPrimaryEnabled,
                        onClick = {
                            scope.launch {
                                when (val result = scanHolder.confirm()) {
                                    is ScanConfirmOutcome.Completed -> {
                                        scanner = false
                                        onDismiss()
                                        navigation.openContact(result.contact)
                                    }
                                    ScanConfirmOutcome.Stay -> Unit
                                }
                            }
                        },
                    ) { Text(nodesString(confirmation.primaryButtonTitle)) }
                },
                dismissButton = {
                    TextButton(onClick = scanHolder::resetToScanner) { Text(stringResource(C.contactsScanScanAgain)) }
                },
                title = { Text(confirmation.displayedName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(nodesString(confirmation.displayedTypeName))
                        confirmation.scannedAsText?.let { Text(nodesString(it)) }
                        Text(confirmation.publicKeyText, fontFamily = FontFamily.Monospace)
                        confirmation.errorMessage?.let { Text(nodesString(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }
}

@Composable
internal fun ContactShareDialog(
    holder: ContactQrShareStateHolder,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val copied by holder.copyFeedback.showing.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val payload = holder.sharePayload(holder.contactName)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType(payload.mimeType)
                            .putExtra(Intent.EXTRA_TEXT, payload.text)
                            .putExtra(Intent.EXTRA_SUBJECT, payload.subject),
                        holder.contactName,
                    ),
                )
            }) { Text(stringResource(C.contactsDetailShareContact)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(C.contactsCommonDone)) } },
        title = { Text(holder.contactName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactQrCode(holder.qrSpec, holder.contactName)
                Text(holder.publicKeyText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    enabled = !copied,
                    onClick = {
                        val text = holder.copyPublicKey()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(holder.contactName, text))
                    },
                ) { Text(if (copied) stringResource(C.contactsQrCopied) else stringResource(C.contactsQrCopy)) }
            }
        },
    )
}
