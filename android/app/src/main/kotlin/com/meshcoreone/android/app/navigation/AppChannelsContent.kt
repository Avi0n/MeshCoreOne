// AndroidOnly: WP-407 Register the implemented channel sheets, with native share/clipboard and camera QR adapters.
package com.meshcoreone.android.app.navigation

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.feature.chats.channels.*
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParser
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import kotlinx.coroutines.launch

@Composable
internal fun AppChannelsContent(feature: AppChatsFeature, coordinator: NavigationCoordinator, onDismiss: () -> Unit) {
    val radio by feature.currentRadioId.collectAsState()
    val holder = remember(feature, radio) {
        ChannelSheetsStateHolder(radio, feature.maximumChannels(), feature.channels)
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    if (invalid) AlertDialog(onDismissRequest = { invalid = false },
        text = { Text(stringResource(L.errorPersistenceInvalidData)) },
        confirmButton = { TextButton(onClick = { invalid = false }) { Text(stringResource(L.commonOk)) } })
    val ready: (com.meshcoreone.android.core.model.ChannelDTO) -> Unit = {
        onDismiss()
        coordinator.navigateToChannel(it)
    }
    if (scanning) {
        AppQrScanner(onDismiss = { scanning = false }, onDecoded = { text ->
            scanning = false
            val link = MeshCoreLinkParser.parseChannelURL(text)
            if (link == null) {
                feature.report("Scanner rejected a non-channel QR code", null)
                invalid = true
            } else {
                scanning = false
                scope.launch {
                    holder.joinLink(ChannelLink(link.name, link.secret, link.regionScope))?.let(ready)
                }
            }
        })
    } else {
        ChannelOptionsSheet(
            holder, onDismiss, ready,
            onScanRequested = { scanning = true },
            onShare = { uri ->
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri), null,
                ))
            },
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            qrCode = { text -> AppQrCode(text) },
        )
    }
}

@Composable
internal fun AppQrCode(text: String) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1) }
    Canvas(Modifier.size(240.dp)) {
        drawRect(Color.White)
        val cell = minOf(size.width, size.height) / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppChannelQr(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            AppQrCode(text)
            Text(text)
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.chatsMessageActionCopy))
            }
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppContactsStrings.contactsQrShare)) }
        }
    }
}
