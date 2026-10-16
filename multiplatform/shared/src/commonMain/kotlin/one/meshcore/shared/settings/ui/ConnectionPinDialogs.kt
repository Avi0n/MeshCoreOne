package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_bluetooth_alert_change
import one.meshcore.shared.resources.settings_bluetooth_alert_change_pin_message
import one.meshcore.shared.resources.settings_bluetooth_alert_change_pin_title
import one.meshcore.shared.resources.settings_bluetooth_alert_change_pin_type_message
import one.meshcore.shared.resources.settings_bluetooth_alert_change_pin_type_title
import one.meshcore.shared.resources.settings_bluetooth_alert_set_pin_message
import one.meshcore.shared.resources.settings_bluetooth_alert_set_pin_title
import one.meshcore.shared.resources.settings_bluetooth_change_pin
import one.meshcore.shared.resources.settings_bluetooth_pin_placeholder
import one.meshcore.shared.resources.settings_bluetooth_set_pin
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.preferenceHubInset
import org.jetbrains.compose.resources.stringResource

internal const val PIN_LENGTH = 6
private const val BLE_PIN_MIN = 100_000L
private const val BLE_PIN_MAX = 999_999L

/** Which PIN-entry / revert dialog is open, if any. */
internal enum class PinDialog { SET, CHANGE, REVERT }

@Composable
internal fun PinDialogHost(
    dialog: PinDialog?,
    pinDraft: String,
    onPinDraftChange: (String) -> Unit,
    invalidPinMessage: String,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onValidationError: (String) -> Unit,
) {
    when (dialog) {
        PinDialog.SET, PinDialog.CHANGE -> PinEntryDialog(
            isChange = dialog == PinDialog.CHANGE,
            value = pinDraft,
            onValueChange = onPinDraftChange,
            onCancel = onDismiss,
            onConfirm = {
                val pin = pinDraft.toLongOrNull()
                onDismiss()
                if (pin == null || pin !in BLE_PIN_MIN..BLE_PIN_MAX) {
                    onValidationError(invalidPinMessage)
                } else {
                    onSave(pin)
                }
            },
        )
        PinDialog.REVERT -> ConfirmAlertDialog(
            title = stringResource(Res.string.settings_bluetooth_alert_change_pin_type_title),
            message = stringResource(Res.string.settings_bluetooth_alert_change_pin_type_message),
            confirmLabel = stringResource(Res.string.settings_bluetooth_alert_change),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                onDismiss()
                onSave(ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT)
            },
            onDismiss = onDismiss,
        )
        null -> Unit
    }
}

@Composable
private fun PinEntryDialog(
    isChange: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    FullScreenOverlay(
        onDismiss = onCancel,
        title = stringResource(
            if (isChange) {
                Res.string.settings_bluetooth_alert_change_pin_title
            } else {
                Res.string.settings_bluetooth_alert_set_pin_title
            },
        ),
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInset(innerPadding)) {
            PreferenceHubSection {
                Column(Modifier.padding(PreferenceHubCardPadding)) {
                    Text(
                        text = stringResource(
                            if (isChange) {
                                Res.string.settings_bluetooth_alert_change_pin_message
                            } else {
                                Res.string.settings_bluetooth_alert_set_pin_message
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    AppOutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text(stringResource(Res.string.settings_bluetooth_pin_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onConfirm,
                enabled = value.length == PIN_LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                Text(
                    if (isChange) {
                        stringResource(Res.string.settings_bluetooth_change_pin)
                    } else {
                        stringResource(Res.string.settings_bluetooth_set_pin)
                    },
                )
            }
        }
    }
}
