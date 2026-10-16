package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_error_not_connected
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_common_status_disconnected
import one.meshcore.shared.resources.settings_trusted_contacts_apply
import one.meshcore.shared.resources.settings_trusted_contacts_favorites_only
import one.meshcore.shared.resources.settings_trusted_contacts_no_contacts
import one.meshcore.shared.resources.settings_trusted_contacts_no_contacts_description
import one.meshcore.shared.resources.settings_trusted_contacts_no_results
import one.meshcore.shared.resources.settings_trusted_contacts_no_results_description
import one.meshcore.shared.resources.settings_trusted_contacts_search_prompt
import one.meshcore.shared.runReportingException
import one.meshcore.shared.settings.TrustedContactsApply
import one.meshcore.shared.settings.TrustedContactsFilter
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Picker for contacts trusted for telemetry (bits 1–3). Matches iOS TrustedContactsPickerView:
 * search, favorites filter, multi-select, Apply when connected and dirty.
 */
@Composable
fun TrustedContactsPickerScreen(
    contactService: ContactService?,
    radioId: Uuid?,
    connectionReady: Boolean,
    isRadioConnected: () -> Boolean = { connectionReady },
    modifier: Modifier = Modifier,
) {
    var contacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pendingTrusted by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var initialTrusted by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var disconnected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val contactErrorText = rememberContactServiceErrorText()
    val unmappedError = stringResource(Res.string.contacts_common_error_occurred)

    LaunchedEffect(contactService, radioId) {
        loaded = false
        val service = contactService
        val radio = radioId
        if (service == null || radio == null) {
            contacts = emptyList()
            pendingTrusted = emptySet()
            initialTrusted = emptySet()
            disconnected = true
            loaded = true
            return@LaunchedEffect
        }
        disconnected = false
        runReportingException(
            onFailure = {
                errorMessage = contactErrorText(it) ?: unmappedError
                null
            },
        ) { service.getContacts(radio) }?.let { all ->
            contacts = all
            val trusted = TrustedContactsFilter.trustedIds(all)
            initialTrusted = trusted
            pendingTrusted = trusted
        }
        loaded = true
    }

    val filtered = remember(contacts, searchText, favoritesOnly) {
        TrustedContactsFilter.filter(contacts, searchText, favoritesOnly)
    }
    val dirty = pendingTrusted != initialTrusted
    val canApply = connectionReady && dirty && !isApplying && contactService != null

    Column(
        modifier.fillMaxSize(),
    ) {
        AppSearchField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = stringResource(Res.string.settings_trusted_contacts_search_prompt),
        )
        PreferenceHubSection(applyHorizontalPadding = false) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_trusted_contacts_favorites_only),
                checked = favoritesOnly,
                onCheckedChange = { favoritesOnly = it },
            )
        }
        if (!loaded) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (disconnected || filtered.isEmpty()) {
            TrustedContactsEmpty(
                disconnected = disconnected,
                searchText = searchText,
                favoritesOnly = favoritesOnly,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id.value }) { contact ->
                    val selected = pendingTrusted.contains(contact.id)
                    ListItem(
                        headlineContent = { Text(contact.displayName) },
                        leadingContent = {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                                enabled = !isApplying,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = selected,
                                enabled = !isApplying,
                                role = Role.Checkbox,
                                onValueChange = {
                                    pendingTrusted = TrustedContactsFilter.toggledTrustedIds(
                                        selected = selected,
                                        pending = pendingTrusted,
                                        id = contact.id,
                                    )
                                },
                            ),
                    )
                }
            }
        }
        Button(
            onClick = {
                isApplying = true
                errorMessage = null
                scope.launch {
                    try {
                        applyTrustedSelection(
                            request = TrustedSelectionRequest(
                                contactService = contactService,
                                isRadioConnected = isRadioConnected(),
                                pendingTrusted = pendingTrusted,
                                initialTrusted = initialTrusted,
                            ),
                            onApplied = {
                                initialTrusted = pendingTrusted
                                haptics.success()
                            },
                            onFailure = { error ->
                                errorMessage = contactErrorText(error) ?: unmappedError
                            },
                        )
                    } finally {
                        isApplying = false
                    }
                }
            },
            enabled = canApply,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            if (isApplying) {
                FilledButtonProgress()
            }
            Text(stringResource(Res.string.settings_trusted_contacts_apply))
        }
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

internal data class TrustedSelectionRequest(
    val contactService: ContactService?,
    val isRadioConnected: Boolean,
    val pendingTrusted: Set<Uuid>,
    val initialTrusted: Set<Uuid>,
)

@Suppress("TooGenericExceptionCaught")
internal suspend fun applyTrustedSelection(
    request: TrustedSelectionRequest,
    onApplied: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        val applied = TrustedContactsApply.apply(
            service = request.contactService,
            isRadioConnected = request.isRadioConnected,
            toGrant = request.pendingTrusted - request.initialTrusted,
            toRevoke = request.initialTrusted - request.pendingTrusted,
        )
        if (applied) onApplied()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}

@Composable
private fun TrustedContactsEmpty(
    disconnected: Boolean,
    searchText: String,
    favoritesOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val isFiltered = !disconnected && (searchText.isNotBlank() || favoritesOnly)
    val (title, body) = when {
        disconnected ->
            stringResource(Res.string.localizable_common_status_disconnected) to
                stringResource(Res.string.contacts_add_error_not_connected)
        isFiltered ->
            stringResource(Res.string.settings_trusted_contacts_no_results) to
                formatTemplate(
                    stringResource(Res.string.settings_trusted_contacts_no_results_description),
                    listOf(searchText),
                )
        else ->
            stringResource(Res.string.settings_trusted_contacts_no_contacts) to
                stringResource(Res.string.settings_trusted_contacts_no_contacts_description)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = trustedContactsEmptyIcon(isFiltered = isFiltered, disconnected = disconnected),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS TrustedContacts empty: `magnifyingglass` when filtered, else `person.2.slash`. */
internal fun trustedContactsEmptyIcon(isFiltered: Boolean, disconnected: Boolean = false) = when {
    disconnected -> AppIcons.CellTowerOff
    isFiltered -> AppIcons.Search
    else -> AppIcons.GroupOff
}
