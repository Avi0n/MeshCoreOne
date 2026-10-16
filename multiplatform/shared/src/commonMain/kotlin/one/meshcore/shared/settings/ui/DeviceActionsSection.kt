package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_device_actions_alert_reboot_confirm
import one.meshcore.shared.resources.settings_device_actions_alert_reboot_message
import one.meshcore.shared.resources.settings_device_actions_alert_reboot_title
import one.meshcore.shared.resources.settings_device_actions_header
import one.meshcore.shared.resources.settings_device_actions_reboot_device
import one.meshcore.shared.resources.settings_device_actions_rebooting
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeviceActionsSection(
    isDeviceConnected: Boolean,
    onReboot: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val settingsErrorText = rememberSettingsErrorText()
    var isRebooting by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PreferenceHubSection(
        modifier = modifier,
        title = stringResource(Res.string.settings_device_actions_header),
        applyHorizontalPadding = false,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    if (isRebooting) {
                        stringResource(Res.string.settings_device_actions_rebooting)
                    } else {
                        stringResource(Res.string.settings_device_actions_reboot_device)
                    },
                )
            },
            leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .radioConnectionHint(isDeviceConnected)
                .clickable(enabled = isDeviceConnected && !isRebooting) { showConfirm = true },
        )
    }

    if (showConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_device_actions_alert_reboot_title),
            message = stringResource(Res.string.settings_device_actions_alert_reboot_message),
            confirmLabel = stringResource(Res.string.settings_device_actions_alert_reboot_confirm),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                showConfirm = false
                launchDeviceReboot(
                    scope,
                    DeviceRebootRequest(
                        onReboot = onReboot,
                        onRebooting = { isRebooting = it },
                        onError = { errorMessage = it },
                        settingsErrorText = settingsErrorText,
                    ),
                )
            },
            onDismiss = { showConfirm = false },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        )
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}
