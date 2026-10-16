package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_import_key_title
import one.meshcore.shared.resources.settings_regenerate_identity_header
import one.meshcore.shared.resources.settings_regenerate_identity_title
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFrame
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced identity entry (iOS `DeviceIdentitySection`): two labeled rows that open
 * import or regenerate, matching `square.and.arrow.down` / `arrow.triangle.2.circlepath`.
 */
@Composable
internal fun DeviceIdentityEntrySection(
    isDeviceConnected: Boolean,
    onImport: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceHubSection(
        modifier = modifier,
        title = stringResource(Res.string.settings_regenerate_identity_header),
        applyHorizontalPadding = false,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_import_key_title)) },
            leadingContent = { Icon(AppIcons.CloudDownload, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .radioConnectionHint(isDeviceConnected)
                .clickable(enabled = isDeviceConnected, onClick = onImport),
        )
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_regenerate_identity_title)) },
            leadingContent = { Icon(AppIcons.Sync, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .radioConnectionHint(isDeviceConnected)
                .clickable(enabled = isDeviceConnected, onClick = onRegenerate),
        )
    }
}

/** Full-screen import or regenerate host, matching iOS `ImportKeySheet` / `RegenerateIdentitySheet`. */
@Composable
internal fun DeviceIdentitySheetHost(
    sheet: DeviceIdentitySheet,
    onImportPrivateKey: suspend (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = when (sheet) {
            DeviceIdentitySheet.IMPORT -> stringResource(Res.string.settings_import_key_title)
            DeviceIdentitySheet.REGENERATE -> stringResource(Res.string.settings_regenerate_identity_title)
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .preferenceHubFrame(),
        ) {
            IdentityKeyActions(
                onImportPrivateKey = onImportPrivateKey,
                showImport = sheet == DeviceIdentitySheet.IMPORT,
                showRegenerate = sheet == DeviceIdentitySheet.REGENERATE,
                onSuccess = onDismiss,
            )
        }
    }
}
