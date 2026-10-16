package one.meshcore.shared.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_device_connected
import one.meshcore.shared.resources.settings_device_info_connection_status
import one.meshcore.shared.resources.settings_device_info_public_key
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** iOS `RegenerateIdentityViewModel` VoiceOver: each public-key byte as `%02X`, joined with `", "`. */
internal fun generatedPublicKeyAccessibilityLabel(publicKey: ByteArray): String =
    publicKey.toUppercaseHex().chunked(HEX_CHARS_PER_BYTE).joinToString(", ")

private const val HEX_CHARS_PER_BYTE = 2

@Composable
internal fun DeviceIdentityHeader(nodeName: String, manufacturer: String) {
    ListItem(
        headlineContent = { Text(nodeName) },
        supportingContent = {
            Text(
                text = manufacturer,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = AppIcons.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PublicKeyRow(enabled: Boolean, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.settings_device_info_public_key)) },
        leadingContent = {
            Icon(imageVector = AppIcons.Key, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen),
    )
}

@Composable
internal fun ConnectionStatusRow() {
    ListItem(
        headlineContent = {
            Text(stringResource(Res.string.settings_device_info_connection_status))
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CONNECTION_DOT_GAP),
            ) {
                Box(
                    modifier = Modifier
                        .size(CONNECTION_DOT_DP.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(
                    text = stringResource(Res.string.settings_device_connected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(imageVector = AppIcons.CellTower, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val CONNECTION_DOT_DP = 8
private val CONNECTION_DOT_GAP = 4.dp
