package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.WifiAddressValidator
import one.meshcore.shared.connection.wifiPortDraftText
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_value_not_set
import one.meshcore.shared.resources.localizable_error_wifi_invalid_host
import one.meshcore.shared.resources.onboarding_wifi_connection_ip_address_clear_accessibility
import one.meshcore.shared.resources.onboarding_wifi_connection_ip_address_placeholder
import one.meshcore.shared.resources.onboarding_wifi_connection_port_clear_accessibility
import one.meshcore.shared.resources.settings_wifi_address
import one.meshcore.shared.resources.settings_wifi_edit_connection_details
import one.meshcore.shared.resources.settings_wifi_edit_error_invalid_port
import one.meshcore.shared.resources.settings_wifi_edit_footer
import one.meshcore.shared.resources.settings_wifi_edit_port_placeholder
import one.meshcore.shared.resources.settings_wifi_edit_reconnecting
import one.meshcore.shared.resources.settings_wifi_edit_save_changes
import one.meshcore.shared.resources.settings_wifi_footer
import one.meshcore.shared.resources.settings_wifi_header
import one.meshcore.shared.resources.settings_wifi_port
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.WIFI_HOST_KEYBOARD
import one.meshcore.shared.ui.WIFI_PORT_KEYBOARD
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.textFieldClearTrailingIcon
import one.meshcore.shared.ui.wifiHostKeyboardActions
import one.meshcore.shared.ui.wifiPortKeyboardActions
import org.jetbrains.compose.resources.stringResource

private const val WIFI_PORT_DRAFT_MAX_DIGITS = 5

/** iOS `WiFiEditSheet` error `exclamationmark.triangle`. */
internal val WIFI_EDIT_ERROR_ICON get() = AppIcons.Warning

/** A missing saved host or port is a fresh endpoint, so the draft always counts as a change. */
internal fun connectionWifiHasChanges(
    savedHost: String?,
    savedPort: Int?,
    hostDraft: String,
    portDraft: String,
): Boolean = savedHost == null ||
    savedPort == null ||
    hostDraft != savedHost ||
    portDraft != savedPort.toString()

internal data class WifiEndpointSave(
    val isSaving: Boolean,
    val hostDraft: String,
    val portDraft: String,
    val hasChanges: Boolean,
    val invalidHostMessage: String,
    val invalidPortMessage: String,
    val onSavingChange: (Boolean) -> Unit,
    val onReconnectingChange: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
    val onSaved: (host: String, port: Int) -> Unit,
    val settingsErrorText: (Throwable) -> String,
)

@Suppress("TooGenericExceptionCaught")
internal fun launchWifiEndpointSave(
    scope: CoroutineScope,
    save: (suspend (host: String, port: Int) -> Unit)?,
    request: WifiEndpointSave,
) {
    val write = save ?: return
    if (request.isSaving) return
    if (!WifiAddressValidator.isValidHost(request.hostDraft)) {
        request.onError(request.invalidHostMessage)
        return
    }
    val port = request.portDraft.toIntOrNull()
    if (port == null || !WifiAddressValidator.isValidPort(request.portDraft)) {
        request.onError(request.invalidPortMessage)
        return
    }
    if (!request.hasChanges) return
    scope.launch {
        request.onSavingChange(true)
        request.onReconnectingChange(true)
        request.onError(null)
        try {
            write(request.hostDraft, port)
            request.onSaved(request.hostDraft, port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.onError(request.settingsErrorText(error))
        } finally {
            request.onSavingChange(false)
            request.onReconnectingChange(false)
        }
    }
}

/** WiFi address readout plus the edit form that reconnects at the new endpoint. */
@Composable
internal fun ConnectionWifiSection(
    snapshot: ConnectionSettingsSnapshot,
    onSaveWifi: (suspend (host: String, port: Int) -> Unit)?,
    onSnapshotChange: (ConnectionSettingsSnapshot) -> Unit,
) {
    var wifiHostDraft by remember(snapshot.wifiHost) { mutableStateOf(snapshot.wifiHost.orEmpty()) }
    var wifiPortDraft by remember(snapshot.wifiPort) { mutableStateOf(wifiPortDraftText(snapshot.wifiPort)) }
    var isSaving by remember { mutableStateOf(false) }
    var isWifiReconnecting by remember { mutableStateOf(false) }
    var wifiErrorMessage by remember { mutableStateOf<String?>(null) }
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    val portFocus = remember { FocusRequester() }
    val invalidPortMessage = stringResource(Res.string.settings_wifi_edit_error_invalid_port)
    val invalidHostMessage = stringResource(Res.string.localizable_error_wifi_invalid_host)
    val wifiHasChanges = connectionWifiHasChanges(
        snapshot.wifiHost,
        snapshot.wifiPort,
        wifiHostDraft,
        wifiPortDraft,
    )
    val saveRequest = WifiEndpointSave(
        isSaving = isSaving,
        hostDraft = wifiHostDraft,
        portDraft = wifiPortDraft,
        hasChanges = wifiHasChanges,
        invalidHostMessage = invalidHostMessage,
        invalidPortMessage = invalidPortMessage,
        onSavingChange = { isSaving = it },
        onReconnectingChange = { isWifiReconnecting = it },
        onError = { wifiErrorMessage = it },
        onSaved = { host, port ->
            connectionSnapshotAfterWifiSaved(snapshot, host, port)?.let(onSnapshotChange)
        },
        settingsErrorText = settingsErrorText,
    )

    if (snapshot.wifiHost != null || snapshot.wifiPort != null) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_wifi_header),
            applyHorizontalPadding = false,
        ) {
            PreferenceHubValueRow(
                stringResource(Res.string.settings_wifi_address),
                connectionUnsetPreferenceText(
                    snapshot.wifiHost,
                    stringResource(Res.string.android_value_not_set),
                ),
                monospace = true,
            )
            PreferenceHubValueRow(
                stringResource(Res.string.settings_wifi_port),
                connectionUnsetPreferenceText(
                    snapshot.wifiPort?.toString(),
                    stringResource(Res.string.android_value_not_set),
                ),
            )
        }
    }
    if (onSaveWifi != null) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_wifi_edit_connection_details),
            applyHorizontalPadding = false,
        ) {
            Column(Modifier.padding(PreferenceHubCardPadding)) {
                AppOutlinedTextField(
                    value = wifiHostDraft,
                    onValueChange = { wifiHostDraft = WifiAddressValidator.sanitizeHostInput(it) },
                    label = {
                        Text(stringResource(Res.string.onboarding_wifi_connection_ip_address_placeholder))
                    },
                    singleLine = true,
                    keyboardOptions = WIFI_HOST_KEYBOARD,
                    keyboardActions = wifiHostKeyboardActions(portFocus),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    enabled = !isWifiReconnecting,
                    trailingIcon = textFieldClearTrailingIcon(
                        value = wifiHostDraft,
                        contentDescription = stringResource(
                            Res.string.onboarding_wifi_connection_ip_address_clear_accessibility,
                        ),
                        enabled = !isWifiReconnecting,
                        onClear = { wifiHostDraft = "" },
                    ),
                )
                AppOutlinedTextField(
                    value = wifiPortDraft,
                    onValueChange = { wifiPortDraft = it.filter(Char::isDigit).take(WIFI_PORT_DRAFT_MAX_DIGITS) },
                    label = { Text(stringResource(Res.string.settings_wifi_edit_port_placeholder)) },
                    singleLine = true,
                    keyboardOptions = WIFI_PORT_KEYBOARD,
                    keyboardActions = wifiPortKeyboardActions {
                        launchWifiEndpointSave(scope, onSaveWifi, saveRequest)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(portFocus),
                    enabled = !isWifiReconnecting,
                    trailingIcon = textFieldClearTrailingIcon(
                        value = wifiPortDraft,
                        contentDescription = stringResource(
                            Res.string.onboarding_wifi_connection_port_clear_accessibility,
                        ),
                        enabled = !isWifiReconnecting,
                        onClear = { wifiPortDraft = "" },
                    ),
                )
            }
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_wifi_edit_footer),
            applyHorizontalPadding = false,
        )
        wifiErrorMessage?.let { message ->
            NodeConfigErrorLabel(message, icon = WIFI_EDIT_ERROR_ICON)
        }
        Button(
            onClick = { launchWifiEndpointSave(scope, onSaveWifi, saveRequest) },
            enabled = !isSaving &&
                WifiAddressValidator.isValidHost(wifiHostDraft) &&
                WifiAddressValidator.isValidPort(wifiPortDraft) &&
                wifiHasChanges,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            if (isWifiReconnecting) {
                FilledButtonProgress()
            }
            Text(
                if (isWifiReconnecting) {
                    stringResource(Res.string.settings_wifi_edit_reconnecting)
                } else {
                    stringResource(Res.string.settings_wifi_edit_save_changes)
                },
            )
        }
    }
    PreferenceHubFooter(
        text = stringResource(Res.string.settings_wifi_footer),
        applyHorizontalPadding = false,
    )
}
