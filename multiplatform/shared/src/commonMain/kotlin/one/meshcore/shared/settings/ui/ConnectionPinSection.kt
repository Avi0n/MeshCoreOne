package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_bluetooth_change_pin
import one.meshcore.shared.resources.settings_bluetooth_current_pin
import one.meshcore.shared.resources.settings_bluetooth_default_pin_footer
import one.meshcore.shared.resources.settings_bluetooth_error_invalid_pin
import one.meshcore.shared.resources.settings_bluetooth_header
import one.meshcore.shared.resources.settings_bluetooth_pin_type
import one.meshcore.shared.resources.settings_bluetooth_pin_type_custom
import one.meshcore.shared.resources.settings_bluetooth_pin_type_default
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

private const val PIN_MASK = "••••••"

/** Whether the PIN section is showing the Default or a Custom PIN, mirroring iOS `BluetoothPinType`. */
private enum class BluetoothPinType { DEFAULT, CUSTOM }

internal data class BlePinSave(
    val pin: Long,
    val onSavingChange: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
    val onSaved: () -> Unit,
    val retryAlert: RetryAlertState,
    val settingsErrorText: (Throwable) -> String,
)

internal fun launchBlePinSave(scope: CoroutineScope, save: (suspend (Long) -> Unit)?, request: BlePinSave) {
    val write = save ?: return
    request.onSavingChange(true)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = SettingsWriteReport(
            retryAlert = request.retryAlert,
            onError = request.onError,
            settingsErrorText = request.settingsErrorText,
            onRetry = { launchBlePinSave(scope, save, request) },
            onMaxRetriesExceeded = {
                // iOS ConnectionSettingsView has no dismiss-on-max-retry; stay on this page.
            },
        ),
        onFinished = { request.onSavingChange(false) },
    ) {
        write(request.pin)
        request.onSaved()
    }
}

/**
 * BLE PIN Default/Custom selector, current-PIN reveal, change/set/revert dialogs. Choosing Custom
 * opens PIN entry; choosing Default confirms then writes the firmware default; both reboot to commit.
 * Mirrors iOS `BluetoothSection`.
 */
@Composable
internal fun BluetoothPinSection(
    snapshot: ConnectionSettingsSnapshot,
    canWrite: Boolean,
    isRadioReady: Boolean,
    isSaving: Boolean,
    onSaveBlePin: (suspend (Long) -> Unit)?,
    onSavingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onPinSaved: (Long) -> Unit,
    retryAlert: RetryAlertState,
) {
    var openDialog by remember { mutableStateOf<PinDialog?>(null) }
    var pinDraft by remember { mutableStateOf("") }
    var isPinVisible by remember(snapshot.blePin) { mutableStateOf(false) }
    val invalidPinMessage = stringResource(Res.string.settings_bluetooth_error_invalid_pin)
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    val selectedType = if (snapshot.isCustomBlePin) BluetoothPinType.CUSTOM else BluetoothPinType.DEFAULT

    PreferenceHubSection(
        title = stringResource(Res.string.settings_bluetooth_header),
        applyHorizontalPadding = false,
    ) {
        if (canWrite) {
            Text(
                text = stringResource(Res.string.settings_bluetooth_pin_type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubCardPadding)
                    .radioConnectionHint(isRadioReady),
            ) {
                SegmentedButton(
                    selected = selectedType == BluetoothPinType.DEFAULT,
                    onClick = {
                        if (selectedType == BluetoothPinType.CUSTOM) openDialog = PinDialog.REVERT
                    },
                    enabled = isRadioReady && !isSaving,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text(stringResource(Res.string.settings_bluetooth_pin_type_default)) },
                )
                SegmentedButton(
                    selected = selectedType == BluetoothPinType.CUSTOM,
                    onClick = {
                        if (selectedType == BluetoothPinType.DEFAULT) {
                            pinDraft = ""
                            openDialog = PinDialog.SET
                        }
                    },
                    enabled = isRadioReady && !isSaving,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text(stringResource(Res.string.settings_bluetooth_pin_type_custom)) },
                )
            }
        } else {
            PreferenceHubValueRow(
                stringResource(Res.string.settings_bluetooth_pin_type),
                if (snapshot.isCustomBlePin) {
                    stringResource(Res.string.settings_bluetooth_pin_type_custom)
                } else {
                    stringResource(Res.string.settings_bluetooth_pin_type_default)
                },
            )
        }

        if (snapshot.isCustomBlePin) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_bluetooth_current_pin)) },
                supportingContent = {
                    Text(
                        text = if (isPinVisible) snapshot.blePin.toString() else PIN_MASK,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (isPinVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isPinVisible = !isPinVisible },
            )
            if (canWrite) {
                Button(
                    onClick = {
                        pinDraft = ""
                        openDialog = PinDialog.CHANGE
                    },
                    enabled = isRadioReady && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp)
                        .radioConnectionHint(isRadioReady),
                ) {
                    Text(stringResource(Res.string.settings_bluetooth_change_pin))
                }
            }
        }
    }

    if (selectedType == BluetoothPinType.DEFAULT) {
        PreferenceHubFooter(
            stringResource(Res.string.settings_bluetooth_default_pin_footer),
            applyHorizontalPadding = false,
        )
    }

    PinDialogHost(
        dialog = openDialog,
        pinDraft = pinDraft,
        onPinDraftChange = { pinDraft = it.filter(Char::isDigit).take(PIN_LENGTH) },
        invalidPinMessage = invalidPinMessage,
        onDismiss = { openDialog = null },
        onSave = { pin ->
            launchBlePinSave(
                scope,
                onSaveBlePin,
                BlePinSave(
                    pin = pin,
                    onSavingChange = onSavingChange,
                    onError = onError,
                    onSaved = { onPinSaved(pin) },
                    retryAlert = retryAlert,
                    settingsErrorText = settingsErrorText,
                ),
            )
        },
        onValidationError = { onError(invalidPinMessage) },
    )
}
