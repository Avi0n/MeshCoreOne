package one.meshcore.shared.contact.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.BlockedContactsViewModel
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_blocked_empty_description
import one.meshcore.shared.resources.contacts_blocked_empty_title
import one.meshcore.shared.resources.contacts_blocked_loading
import one.meshcore.shared.resources.contacts_blocked_title
import one.meshcore.shared.ui.OptionalNestedScaffold
import one.meshcore.shared.ui.expressiveListItem
import org.jetbrains.compose.resources.stringResource

/**
 * Blocked contacts list driven by [BlockedContactsViewModel]. Row tap opens contact detail so the
 * user can unblock from the detail screen.
 */
@Composable
fun BlockedContactsScreen(
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    onBack: () -> Unit,
    onContactTap: (ContactDto) -> Unit,
    nowSeconds: Long,
    /** The connected radio's key-prefix width in bytes (iOS `connectedDevice?.hashSize ?? 1`). */
    hashSize: Int,
    listInvalidations: Flow<Unit> = emptyFlow(),
    modifier: Modifier = Modifier,
    /** False when a parent [Scaffold] already titled this destination (Settings hub). */
    showAppBar: Boolean = true,
) {
    val errorText = rememberContactServiceErrorText()
    val scope = rememberCoroutineScope()
    val currentService = contactService()
    val currentRadioId = radioId()
    val viewModel = rememberBlockedContactsViewModel(scope, contactService, radioId, errorText)
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, listInvalidations, currentService, currentRadioId) {
        viewModel.load()
        listInvalidations.collect { viewModel.load() }
    }
    // Device 0x80/0x8F write Room without the app-side invalidation; iOS reloads off contactsVersion.
    LaunchedEffect(viewModel, currentService) {
        viewModel.bindServiceEvents()
    }

    val body: @Composable (PaddingValues) -> Unit = { innerPadding ->
        when {
            state.isLoading && !state.hasLoadedOnce -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(Res.string.contacts_blocked_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            state.hasLoadedOnce && state.contacts.isEmpty() -> {
                BlockedEmptyPane(
                    title = stringResource(Res.string.contacts_blocked_empty_title),
                    description = stringResource(Res.string.contacts_blocked_empty_description),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                )
            }
            else -> {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    items(state.contacts, key = { it.id.value }) { contact ->
                        ContactRowContent(
                            contact = contact,
                            showTypeLabel = false,
                            hashSize = hashSize,
                            isTogglingFavorite = false,
                            inboundHopCount = null,
                            userLocation = null,
                            nowSeconds = nowSeconds,
                            modifier = expressiveListItem(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onContactTap(contact) },
                            ),
                        )
                    }
                }
            }
        }
    }
    OptionalNestedScaffold(
        showAppBar = showAppBar,
        title = stringResource(Res.string.contacts_blocked_title),
        onBack = onBack,
        modifier = modifier,
        content = body,
    )
    state.errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = viewModel::clearError)
    }
}

@Composable
internal fun rememberBlockedContactsViewModel(
    scope: CoroutineScope,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    errorText: (Throwable) -> String,
): BlockedContactsViewModel {
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val currentErrorText by rememberUpdatedState(errorText)
    return remember(scope) {
        BlockedContactsViewModel(
            scope = scope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { currentContactService() },
                radioId = { currentRadioId() },
                errorText = { error -> currentErrorText(error) },
            ),
        )
    }
}
