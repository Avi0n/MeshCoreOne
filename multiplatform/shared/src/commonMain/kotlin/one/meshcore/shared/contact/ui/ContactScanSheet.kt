package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ScanContactController
import one.meshcore.shared.contact.ScanContactState
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.platformName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_scan_camera_permission_message
import one.meshcore.shared.resources.contacts_add_add
import one.meshcore.shared.resources.contacts_add_node_title
import one.meshcore.shared.resources.contacts_add_public_key
import one.meshcore.shared.resources.contacts_add_scanned_as
import one.meshcore.shared.resources.contacts_add_view
import one.meshcore.shared.resources.contacts_add_view_accessibility
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.contacts_list_open_settings
import one.meshcore.shared.resources.contacts_node_kind_contact
import one.meshcore.shared.resources.contacts_node_kind_repeater
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.resources.contacts_scan_importing
import one.meshcore.shared.resources.contacts_scan_instruction
import one.meshcore.shared.resources.contacts_scan_permission_description
import one.meshcore.shared.resources.contacts_scan_permission_title
import one.meshcore.shared.resources.contacts_scan_scan_again
import one.meshcore.shared.resources.contacts_scan_title
import one.meshcore.shared.resources.contacts_scan_unavailable_description
import one.meshcore.shared.resources.contacts_scan_unavailable_title
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.ScanPermissionDenied
import one.meshcore.shared.ui.ScanQrOverlayCaption
import one.meshcore.shared.ui.ScanQrViewfinderFrame
import one.meshcore.shared.ui.ScanUnavailable
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A camera scanner slot: it feeds decoded strings to [onDecoded] and reports a permission denial. */
typealias ContactQrScanner = @Composable (onDecoded: (String) -> Unit, onPermissionDenied: () -> Unit) -> Unit

/**
 * The contact-QR scan screen: a camera preview with a scan-frame overlay and an instruction/importing/error
 * caption, or a camera-permission prompt. The Kotlin analogue of the iOS `ScanContactQRView`, driven by
 * [controller]; the scan → parse → review → add logic is the controller's, so this composable only renders state.
 *
 * [scanner] is the platform camera slot. Android injects CameraX plus ML Kit; the default renders the
 * unavailable state so desktop and tests compose without a camera.
 * [onOpenSettings] and [onScanSuccess] are platform closures (app-settings deep link, success haptic).
 */
@Composable
fun ContactScanScreen(
    controller: ScanContactController,
    modifier: Modifier = Modifier,
    scanner: ContactQrScanner = { _, _ -> ScannerUnavailable() },
    onOpenSettings: () -> Unit = {},
    onScanSuccess: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    LaunchedEffect(state.selectionTick) {
        if (state.selectionTick > 0) haptics.selection()
    }
    LaunchedEffect(state.errorTick) {
        if (state.errorTick > 0) haptics.failure()
    }

    LaunchedEffect(state.successTick) {
        if (state.successTick > 0) onScanSuccess()
    }

    if (state.scannedContact != null) {
        ScannedContactReview(
            state = state,
            onPrimaryAction = controller::onPrimaryAction,
            onScanAgain = controller::resetToScanner,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (state.permissionDenied) {
        PermissionDenied(onOpenSettings = onOpenSettings, modifier = modifier.fillMaxSize())
        return
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        scanner(controller::onDecoded, controller::onPermissionDenied)

        ScanQrViewfinderFrame()

        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 50.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val scanError = state.error
            when {
                state.isResolvingScan -> {
                    ScanQrOverlayCaption(
                        text = stringResource(Res.string.contacts_scan_importing),
                        leading = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(SCAN_IMPORTING_SPINNER_SIZE),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                strokeWidth = SCAN_IMPORTING_SPINNER_STROKE,
                            )
                        },
                    )
                }
                scanError != null -> ScanQrOverlayCaption(
                    text = scanError.message(),
                    error = true,
                    onClick = controller::clearError,
                )
                else -> ScanQrOverlayCaption(
                    text = stringResource(Res.string.contacts_scan_instruction),
                )
            }
        }
    }
}

internal data class ScannedContactKind(val icon: ImageVector, val label: StringResource)

internal fun scannedContactKind(type: ContactType): ScannedContactKind = when (type) {
    ContactType.CHAT -> ScannedContactKind(AppIcons.Person, Res.string.contacts_node_kind_contact)
    ContactType.REPEATER -> ScannedContactKind(AppIcons.CellTower, Res.string.contacts_node_kind_repeater)
    ContactType.ROOM -> ScannedContactKind(AppIcons.Forum, Res.string.contacts_node_kind_room)
}

/** Read-only identity review between a valid camera decode and any radio mutation. */
@Composable
private fun ScannedContactReview(
    state: ScanContactState,
    onPrimaryAction: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scanned = state.scannedContact ?: return
    val existing = state.existingContact
    val name = existing?.displayName ?: scanned.name
    val type = existing?.contactType ?: scanned.contactType
    val kind = scannedContactKind(type)
    val icon = kind.icon
    val typeLabel = stringResource(kind.label)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(typeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existing != null && scanned.name != name) {
            Text(
                formattedStringResource(Res.string.contacts_add_scanned_as, scanned.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(Res.string.contacts_add_public_key),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectionContainer {
            Text(
                scanned.publicKey.toUppercaseHex().chunked(2).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        state.error?.let { error ->
            Text(error.message(), color = MaterialTheme.colorScheme.error)
        }
        val viewDescription = existing?.let {
            formattedStringResource(Res.string.contacts_add_view_accessibility, name)
        }
        val primaryModifier = if (viewDescription != null) {
            Modifier.fillMaxWidth().semantics {
                contentDescription = viewDescription
            }
        } else {
            Modifier.fillMaxWidth()
        }
        Button(onClick = onPrimaryAction, enabled = !state.isImporting, modifier = primaryModifier) {
            if (state.isImporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(if (existing == null) Res.string.contacts_add_add else Res.string.contacts_add_view))
        }
        Button(onClick = onScanAgain, enabled = !state.isImporting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.contacts_scan_scan_again))
        }
    }
}

@Composable
private fun PermissionDenied(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    ScanPermissionDenied(
        icon = CONTACT_SCAN_PERMISSION_DENIED_ICON,
        glyphSize = EMPTY_STATE_ICON_SIZE,
        title = stringResource(Res.string.contacts_scan_permission_title),
        message = stringResource(
            if (platformName() == "Android") {
                Res.string.android_scan_camera_permission_message
            } else {
                Res.string.contacts_scan_permission_description
            },
        ),
        settingsLabel = stringResource(Res.string.contacts_list_open_settings),
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/** The no-camera fallback, mirroring the iOS `ContentUnavailableView` for devices without a scanner. */
@Composable
fun ScannerUnavailable(modifier: Modifier = Modifier) {
    ScanUnavailable(
        icon = CONTACT_SCAN_UNAVAILABLE_ICON,
        glyphSize = EMPTY_STATE_ICON_SIZE,
        title = stringResource(Res.string.contacts_scan_unavailable_title),
        message = stringResource(Res.string.contacts_scan_unavailable_description),
        modifier = modifier,
    )
}

/**
 * Builds a [ScanContactController] from the live per-connection providers, scopes it to the composition,
 * and hosts [ContactScanScreen]. [onScanned] receives the saved row after Add or View. Kept
 * parameterized so the contact-UI package stays independent of the shell, mirroring [ContactListRoot].
 */
@Composable
fun ContactScanRoot(
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    isRadioConnected: () -> Boolean = { false },
    maxContacts: () -> Int?,
    now: () -> Long,
    onScanned: (ContactDto) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scanner: ContactQrScanner = { _, _ -> ScannerUnavailable() },
    onOpenSettings: () -> Unit = {},
    onScanSuccess: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val errorText = rememberContactServiceErrorText()
    val unmappedError = stringResource(Res.string.contacts_common_error_occurred)
    val currentErrorText by rememberUpdatedState(errorText)
    val currentUnmappedError by rememberUpdatedState(unmappedError)
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val currentIsRadioConnected by rememberUpdatedState(isRadioConnected)
    val currentMaxContacts by rememberUpdatedState(maxContacts)
    val currentNow by rememberUpdatedState(now)
    val currentOnScanned by rememberUpdatedState(onScanned)
    val controller = remember(scope) {
        ScanContactController(
            scope = scope,
            dependencies = ScanContactController.Dependencies(
                contactService = { currentContactService() },
                radioId = { currentRadioId() },
                isRadioConnected = { currentIsRadioConnected() },
                maxContacts = { currentMaxContacts() },
                now = { currentNow() },
                onScanned = { contact -> currentOnScanned(contact) },
                errorText = { error -> currentErrorText(error) },
                unmappedError = { currentUnmappedError },
            ),
        )
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val existing = state.existingContact
    val title = when {
        state.scannedContact == null -> stringResource(Res.string.contacts_scan_title)
        existing != null -> existing.displayName
        else -> stringResource(Res.string.contacts_add_node_title)
    }
    Scaffold(
        topBar = { AppNestedTopBar(title = title, onBack = onBack) },
        modifier = modifier,
    ) { padding ->
        ContactScanScreen(
            controller = controller,
            modifier = Modifier.padding(padding),
            scanner = scanner,
            onOpenSettings = onOpenSettings,
            onScanSuccess = onScanSuccess,
        )
    }
}

/** iOS `ScanContactQRView` unavailable: `qrcode.viewfinder`. */
internal val CONTACT_SCAN_UNAVAILABLE_ICON get() = AppIcons.QrCodeScanner

/** Camera-denied empty uses the same viewfinder glyph as the no-scanner fallback. */
internal val CONTACT_SCAN_PERMISSION_DENIED_ICON get() = AppIcons.QrCodeScanner

private val SCAN_IMPORTING_SPINNER_SIZE = 18.dp
private val SCAN_IMPORTING_SPINNER_STROKE = 2.dp
