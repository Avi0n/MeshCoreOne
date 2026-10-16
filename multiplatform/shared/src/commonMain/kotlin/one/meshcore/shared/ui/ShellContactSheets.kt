package one.meshcore.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.AddContactController
import one.meshcore.shared.contact.AddContactState
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.ui.ContactQrShareSheet
import one.meshcore.shared.contact.ui.message
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_add
import one.meshcore.shared.resources.contacts_add_error_not_connected
import one.meshcore.shared.resources.contacts_add_node_title
import one.meshcore.shared.resources.contacts_add_public_key
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_node_kind_contact
import one.meshcore.shared.resources.contacts_node_kind_repeater
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.resources.localizable_common_status_disconnected
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ShareMyContactSheet(deps: ShellDependencies, onDone: () -> Unit) {
    val publicKey = deps.selfPublicKey()
    val nodeName by deps.localNodeName.collectAsStateWithLifecycle()
    if (publicKey == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    ContactQrShareSheet(
        contactName = nodeName.orEmpty(),
        publicKey = publicKey,
        contactType = ContactType.CHAT,
        onDone = onDone,
        encode = deps.qrEncode,
        onCopy = deps.copyToClipboard,
        onShare = { text, subject -> deps.sharePlainText(text, subject) },
    )
}

/**
 * Confirmation sheet for a `meshcore://contact/add` link tapped in a chat message (iOS
 * `AddContactConfirmationSheet`). Security-relevant: unlike the manual Add-contact form, none of name,
 * type, or public key are editable — the sender-controlled name is de-emphasized while the verifiable
 * public key gets primary visual weight, so a planted link or QR cannot phish a user into trusting a
 * spoofed name over its key. [controller] still drives the add itself so this stays on the one submit
 * path the manual form uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmAddContactSheet(
    contact: MeshCoreDeepLink.Contact,
    deps: ShellDependencies,
    onDone: () -> Unit,
    onAdded: (String?) -> Unit,
) {
    val errorText = rememberContactServiceErrorText()
    val currentErrorText by rememberUpdatedState(errorText)
    val currentDeps by rememberUpdatedState(deps)
    val controller = remember(contact) {
        AddContactController(
            AddContactController.Dependencies(
                contactService = { currentDeps.contactService() },
                radioId = { currentDeps.currentRadioId() },
                isRadioConnected = { currentDeps.connectionState.value.isConnected },
                maxContacts = { currentDeps.maxContacts() },
                now = { currentDeps.nowSeconds() },
                errorText = { error -> currentErrorText(error) },
            ),
        ).apply {
            setName(contact.name)
            setType(contact.type)
            setPublicKeyHex(contact.publicKey.toUppercaseHex())
        }
    }
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val isConnected = deps.connectionState.value.isConnected
    val haptics = rememberAppHaptics()
    LaunchedEffect(state.error) {
        if (state.error != null) haptics.failure()
    }

    Scaffold(
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_add_node_title),
                onBack = onDone,
            )
        },
    ) { padding ->
        if (!isConnected) {
            ConfirmAddContactDisconnected(modifier = Modifier.padding(padding), onDismiss = onDone)
        } else {
            ConfirmAddContactContent(
                contact = contact,
                state = state,
                modifier = Modifier.padding(padding),
                onAdd = {
                    scope.launch {
                        controller.add { id ->
                            haptics.success()
                            onAdded(id?.value)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ConfirmAddContactDisconnected(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = CONFIRM_ADD_CONTACT_DISCONNECTED_ICON,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
            )
            Text(
                text = stringResource(Res.string.localizable_common_status_disconnected),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.contacts_add_error_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(stringResource(Res.string.contacts_common_ok))
        }
    }
}

@Composable
private fun ConfirmAddContactContent(
    contact: MeshCoreDeepLink.Contact,
    state: AddContactState,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = confirmContactIcon(contact.type),
            contentDescription = null,
            modifier = Modifier
                .size(CONFIRM_ICON_CIRCLE_DP.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(CONFIRM_ICON_PADDING_DP.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        // The name is free text the sender controls; the public key is the verifiable identity, so the
        // key below gets primary prominence to defeat name-over-key phishing from a planted link/QR.
        Text(
            text = contact.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = confirmContactTypeLabel(contact.type),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.contacts_add_public_key),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = contact.publicKey.toUppercaseHex().chunked(2).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
            )
        }
        state.error?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onAdd, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
            if (state.isSubmitting) {
                FilledButtonProgress()
            }
            Text(stringResource(Res.string.contacts_add_add))
        }
    }
}

private fun confirmContactIcon(type: ContactType): ImageVector = when (type) {
    ContactType.CHAT -> AppIcons.Person
    ContactType.REPEATER -> AppIcons.CellTower
    ContactType.ROOM -> AppIcons.Forum
}

@Composable
private fun confirmContactTypeLabel(type: ContactType): String = when (type) {
    ContactType.CHAT -> stringResource(Res.string.contacts_node_kind_contact)
    ContactType.REPEATER -> stringResource(Res.string.contacts_node_kind_repeater)
    ContactType.ROOM -> stringResource(Res.string.contacts_node_kind_room)
}

/** iOS `AddContactConfirmationSheet` disconnected: `antenna.radiowaves.left.and.right.slash`. */
internal val CONFIRM_ADD_CONTACT_DISCONNECTED_ICON get() = AppIcons.CellTowerOff

private const val CONFIRM_ICON_CIRCLE_DP = 80

private const val CONFIRM_ICON_PADDING_DP = 20
