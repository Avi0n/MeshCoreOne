package one.meshcore.shared.contact.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.AddContactController
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.PUBLIC_KEY_HEX_LENGTH
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_add
import one.meshcore.shared.resources.contacts_add_character_count
import one.meshcore.shared.resources.contacts_add_contact_name
import one.meshcore.shared.resources.contacts_add_error_invalid_url
import one.meshcore.shared.resources.contacts_add_hex_placeholder
import one.meshcore.shared.resources.contacts_add_name
import one.meshcore.shared.resources.contacts_add_paste_url
import one.meshcore.shared.resources.contacts_add_paste_url_footer
import one.meshcore.shared.resources.contacts_add_public_key
import one.meshcore.shared.resources.contacts_add_public_key_footer
import one.meshcore.shared.resources.contacts_add_scan_qr
import one.meshcore.shared.resources.contacts_add_title
import one.meshcore.shared.resources.contacts_add_type
import one.meshcore.shared.resources.contacts_add_valid
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

private const val HEX_RADIX = 16

/** Keeps only hex digits and lowercases them — the normalized form the public-key field displays. */
private fun String.hexOnlyLower(): String = filter { it.digitToIntOrNull(HEX_RADIX) != null }.lowercase()

/**
 * The manual Add-contact form: a Scan-QR shortcut, a type picker, name and public-key fields with live
 * validation, and a Paste-URL prefill. The Kotlin analogue of the iOS `AddContactSheet`, driven entirely
 * by [controller]. [onScanRequested] opens the QR scanner; [onCancel]/[onAdded] dismiss.
 *
 * [readClipboard] supplies the clipboard text for the Paste-URL action (a platform closure, since the
 * shared module has no clipboard read); it defaults to empty so the form still composes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    controller: AddContactController,
    onScanRequested: () -> Unit,
    onCancel: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
    readClipboard: () -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    // The iOS `AddContactConfirmationSheet` success/error sensory feedback: the add outcome, felt once.
    LaunchedEffect(state.error) {
        if (state.error != null) haptics.failure()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_add_title),
                onBack = onCancel,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                PreferenceHubSection {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.contacts_add_scan_qr)) },
                        leadingContent = {
                            Icon(
                                imageVector = AppIcons.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isSubmitting, onClick = onScanRequested),
                    )
                }
                PreferenceHubSection(title = stringResource(Res.string.contacts_add_type)) {
                    TypePicker(
                        selected = state.type,
                        enabled = !state.isSubmitting,
                        onSelect = controller::setType,
                    )
                }
                PreferenceHubSection {
                    Column(
                        modifier = Modifier.padding(PreferenceHubCardPadding),
                        verticalArrangement = Arrangement.spacedBy(PreferenceHubCardPadding),
                    ) {
                        NameField(
                            name = state.name,
                            enabled = !state.isSubmitting,
                            onChange = controller::setName,
                        )
                        PublicKeyField(
                            hex = state.publicKeyHex,
                            normalizedCount = state.normalizedPublicKeyHex.length,
                            isValid = state.isValidPublicKey,
                            enabled = !state.isSubmitting,
                            onChange = { controller.setPublicKeyHex(it.hexOnlyLower()) },
                        )
                    }
                }
                PreferenceHubFooter(
                    formattedStringResource(Res.string.contacts_add_public_key_footer, PUBLIC_KEY_HEX_LENGTH),
                )
                OutlinedButton(
                    onClick = { controller.pasteUrl(readClipboard()) },
                    enabled = !state.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PreferenceHubHorizontalPadding),
                ) { Text(stringResource(Res.string.contacts_add_paste_url)) }
                if (state.pasteFailed) {
                    Text(
                        stringResource(Res.string.contacts_add_error_invalid_url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
                    )
                } else {
                    PreferenceHubFooter(stringResource(Res.string.contacts_add_paste_url_footer))
                }

                state.error?.let { error ->
                    Text(
                        error.message(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
                    )
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        controller.add {
                            haptics.success()
                            onAdded()
                        }
                    }
                },
                enabled = state.canAdd && !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                if (state.isSubmitting) {
                    FilledButtonProgress()
                }
                Text(stringResource(Res.string.contacts_add_add))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypePicker(selected: ContactType, enabled: Boolean, onSelect: (ContactType) -> Unit) {
    val types = ContactType.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PreferenceHubCardPadding),
    ) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected,
                onClick = { if (enabled) onSelect(type) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
            ) {
                Text(type.kindLabel(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun NameField(name: String, enabled: Boolean, onChange: (String) -> Unit) {
    AppOutlinedTextField(
        value = name,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(Res.string.contacts_add_name)) },
        placeholder = { Text(stringResource(Res.string.contacts_add_contact_name)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PublicKeyField(
    hex: String,
    normalizedCount: Int,
    isValid: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppOutlinedTextField(
            value = hex,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(Res.string.contacts_add_public_key)) },
            placeholder = {
                Text(formattedStringResource(Res.string.contacts_add_hex_placeholder, PUBLIC_KEY_HEX_LENGTH))
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hex.isNotEmpty()) {
            val tint = if (isValid) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ADD_CONTACT_KEY_STATUS_ICON_GAP),
            ) {
                Icon(
                    imageVector = if (isValid) ADD_CONTACT_VALID_ICON else ADD_CONTACT_INVALID_COUNT_ICON,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(ADD_CONTACT_KEY_STATUS_ICON_SIZE),
                )
                Text(
                    if (isValid) {
                        stringResource(Res.string.contacts_add_valid)
                    } else {
                        formattedStringResource(
                            Res.string.contacts_add_character_count,
                            normalizedCount,
                            PUBLIC_KEY_HEX_LENGTH,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
        }
    }
}

/**
 * Builds an [AddContactController] from the live per-connection providers, scopes it to the composition,
 * and hosts [AddContactScreen]. Kept parameterized so the contact-UI package stays independent of the
 * shell, mirroring [ContactListRoot].
 */
@Composable
fun AddContactRoot(
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    isRadioConnected: () -> Boolean = { false },
    maxContacts: () -> Int?,
    now: () -> Long,
    onScanRequested: () -> Unit,
    onCancel: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
    readClipboard: () -> String? = { null },
) {
    val errorText = rememberContactServiceErrorText()
    val currentErrorText by rememberUpdatedState(errorText)
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val currentIsRadioConnected by rememberUpdatedState(isRadioConnected)
    val currentMaxContacts by rememberUpdatedState(maxContacts)
    val currentNow by rememberUpdatedState(now)
    val controller = remember {
        AddContactController(
            AddContactController.Dependencies(
                contactService = { currentContactService() },
                radioId = { currentRadioId() },
                isRadioConnected = { currentIsRadioConnected() },
                maxContacts = { currentMaxContacts() },
                now = { currentNow() },
                errorText = { error -> currentErrorText(error) },
            ),
        )
    }
    AddContactScreen(
        controller = controller,
        onScanRequested = onScanRequested,
        onCancel = onCancel,
        onAdded = onAdded,
        modifier = modifier,
        readClipboard = readClipboard,
    )
}

private val ADD_CONTACT_KEY_STATUS_ICON_SIZE = 14.dp
private val ADD_CONTACT_KEY_STATUS_ICON_GAP = 4.dp

/** iOS `AddContactSheet` valid `checkmark.circle.fill`. */
internal val ADD_CONTACT_VALID_ICON get() = AppIcons.CheckCircle

/** iOS `AddContactSheet` character-count `exclamationmark.triangle.fill`. */
internal val ADD_CONTACT_INVALID_COUNT_ICON get() = AppIcons.Warning
