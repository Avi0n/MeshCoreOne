package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_import_key_error_invalid_hex
import one.meshcore.shared.resources.settings_import_key_error_invalid_key
import one.meshcore.shared.resources.settings_import_key_import
import one.meshcore.shared.resources.settings_import_key_importing
import one.meshcore.shared.resources.settings_import_key_key_input_label
import one.meshcore.shared.resources.settings_import_key_key_input_placeholder
import one.meshcore.shared.resources.settings_import_key_sheet_explanation
import one.meshcore.shared.resources.settings_import_key_sheet_title
import one.meshcore.shared.resources.settings_regenerate_identity_alert_replace_confirm
import one.meshcore.shared.resources.settings_regenerate_identity_alert_replace_message
import one.meshcore.shared.resources.settings_regenerate_identity_alert_replace_title
import one.meshcore.shared.resources.settings_regenerate_identity_error_device_rejected
import one.meshcore.shared.resources.settings_regenerate_identity_error_feature_disabled
import one.meshcore.shared.settings.sanitizePrivateKeyHexInput
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IdentityKeyActions(
    onImportPrivateKey: suspend (ByteArray) -> Unit,
    showImport: Boolean = true,
    showRegenerate: Boolean = true,
    onSuccess: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val rejectedMessage = stringResource(Res.string.settings_regenerate_identity_error_device_rejected)
    val featureDisabledMessage = stringResource(Res.string.settings_regenerate_identity_error_feature_disabled)
    val invalidHexMessage = stringResource(Res.string.settings_import_key_error_invalid_hex)
    val invalidKeyMessage = stringResource(Res.string.settings_import_key_error_invalid_key)
    val sessionText = rememberSettingsErrorText()

    if (showImport) {
        ImportPrivateKeySection(
            busy = busy,
            rejectedMessage = rejectedMessage,
            featureDisabledMessage = featureDisabledMessage,
            invalidHexMessage = invalidHexMessage,
            invalidKeyMessage = invalidKeyMessage,
            sessionText = sessionText,
            onBusyChange = { busy = it },
            onError = { errorMessage = it },
            onImportPrivateKey = onImportPrivateKey,
            onSuccess = onSuccess,
            haptics = haptics,
            scope = scope,
        )
    }
    if (showRegenerate) {
        RegenerateIdentitySection(
            busy = busy,
            rejectedMessage = rejectedMessage,
            featureDisabledMessage = featureDisabledMessage,
            sessionText = sessionText,
            onBusyChange = { busy = it },
            onError = { errorMessage = it },
            onImportPrivateKey = onImportPrivateKey,
            onSuccess = onSuccess,
            haptics = haptics,
            scope = scope,
        )
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

@Composable
private fun ImportPrivateKeySection(
    busy: Boolean,
    rejectedMessage: String,
    featureDisabledMessage: String,
    invalidHexMessage: String,
    invalidKeyMessage: String,
    sessionText: (Throwable) -> String,
    onBusyChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onImportPrivateKey: suspend (ByteArray) -> Unit,
    onSuccess: () -> Unit = {},
    haptics: AppHaptics,
    scope: CoroutineScope,
) {
    var importHex by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf<ByteArray?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    PreferenceHubSection(title = stringResource(Res.string.settings_import_key_sheet_title)) {
        Column(Modifier.padding(PreferenceHubCardPadding)) {
            Text(
                text = stringResource(Res.string.settings_import_key_sheet_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AppOutlinedTextField(
                value = importHex,
                onValueChange = {
                    importHex = sanitizePrivateKeyHexInput(it)
                    onError(null)
                },
                label = { Text(stringResource(Res.string.settings_import_key_key_input_label)) },
                placeholder = { Text(stringResource(Res.string.settings_import_key_key_input_placeholder)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = false,
                minLines = 2,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            OutlinedButton(
                onClick = {
                    when (val step = importKeyStep(importHex)) {
                        ImportKeyStep.InvalidHex -> onError(invalidHexMessage)
                        ImportKeyStep.InvalidKey -> onError(invalidKeyMessage)
                        is ImportKeyStep.Confirm -> {
                            onError(null)
                            pendingKey = step.key
                        }
                    }
                },
                enabled = !busy && importHex.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    }
                    Text(
                        if (isImporting) {
                            stringResource(Res.string.settings_import_key_importing)
                        } else {
                            stringResource(Res.string.settings_import_key_import)
                        },
                    )
                }
            }
        }
    }

    pendingKey?.let { key ->
        ReplaceIdentityConfirmDialog(
            onDismiss = { pendingKey = null },
            onConfirm = {
                pendingKey = null
                launchPrivateKeyImport(
                    scope,
                    PrivateKeyImport(
                        key = key,
                        featureDisabledMessage = featureDisabledMessage,
                        rejectedMessage = rejectedMessage,
                        sessionText = sessionText,
                        onError = onError,
                        onBusyChange = onBusyChange,
                        onImportingChange = { isImporting = it },
                        onImport = onImportPrivateKey,
                        onCleared = { importHex = "" },
                        onHapticSuccess = { haptics.success() },
                        onSuccess = onSuccess,
                    ),
                )
            },
        )
    }
}

/** The destructive Replace-identity confirmation shared by the import and regenerate key paths. */
@Composable
internal fun ReplaceIdentityConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmAlertDialog(
        title = stringResource(Res.string.settings_regenerate_identity_alert_replace_title),
        message = stringResource(Res.string.settings_regenerate_identity_alert_replace_message),
        confirmLabel = stringResource(Res.string.settings_regenerate_identity_alert_replace_confirm),
        dismissLabel = stringResource(Res.string.localizable_common_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    )
}
