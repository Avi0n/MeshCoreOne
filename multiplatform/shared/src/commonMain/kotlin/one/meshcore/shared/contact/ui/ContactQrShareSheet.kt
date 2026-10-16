package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.meshcore.shared.contact.ContactShareContent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_public_key
import one.meshcore.shared.resources.contacts_qr_copied
import one.meshcore.shared.resources.contacts_qr_copy
import one.meshcore.shared.resources.contacts_qr_share
import one.meshcore.shared.resources.contacts_qr_share_subject
import one.meshcore.shared.resources.contacts_qr_title
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PreferenceHubSelectableValueRow
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Shows a contact's QR code (of the `meshcore://contact/add` URI), its display name and space-grouped
 * uppercase-hex key, and Copy/Share actions. The Kotlin analogue of the iOS `ContactQRShareSheet`,
 * reachable both for the connected device's own identity ("Share My Contact") and for a specific contact
 * ("Share Contact"). The QR encoder and the platform copy/share actions are injected: Android supplies the
 * encoder, a null encoder renders the placeholder (desktop and tests), and copy/share are platform
 * closures (`ClipboardManager` / `ACTION_SEND`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactQrShareSheet(
    contactName: String,
    publicKey: ByteArray,
    contactType: ContactType,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    encode: QrEncoder = { _, _ -> null },
    onCopy: (String) -> Unit = {},
    onShare: (text: String, subject: String) -> Unit = { _, _ -> },
) {
    val uri = remember(contactName, publicKey, contactType) {
        ContactShareContent.uri(name = contactName, publicKey = publicKey, type = contactType)
    }
    val spacedKey = remember(publicKey) { publicKey.toUppercaseHex().chunked(HEX_PER_BYTE).joinToString(" ") }
    val shareSubject = stringResource(Res.string.contacts_qr_share_subject)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_qr_title),
                onClose = onDone,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                QrImage(payload = uri, encode = encode)

                Text(contactName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

                PreferenceHubSelectableValueRow(
                    stringResource(Res.string.contacts_add_public_key),
                    spacedKey,
                )
            }
            CopyButton(onCopy = { onCopy(ContactShareContent.compactPublicKeyHex(publicKey)) })
            OutlinedButton(
                onClick = { onShare(uri, shareSubject) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.contacts_qr_share))
            }
        }
    }
}

@Composable
private fun CopyButton(onCopy: () -> Unit) {
    val haptics = rememberAppHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(ContactShareContent.COPY_FEEDBACK_MS)
            copied = false
        }
    }
    Button(
        onClick = {
            onCopy()
            copied = true
            haptics.success()
        },
        enabled = !copied,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(if (copied) Res.string.contacts_qr_copied else Res.string.contacts_qr_copy),
        )
    }
}

private const val HEX_PER_BYTE = 2
