package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.WifiAddressValidator
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_wifi_connection_connect
import one.meshcore.shared.resources.onboarding_wifi_connection_connecting
import one.meshcore.shared.resources.onboarding_wifi_connection_connection_details_footer
import one.meshcore.shared.resources.onboarding_wifi_connection_connection_details_header
import one.meshcore.shared.resources.onboarding_wifi_connection_error_invalid_port
import one.meshcore.shared.resources.onboarding_wifi_connection_ip_address_clear_accessibility
import one.meshcore.shared.resources.onboarding_wifi_connection_ip_address_placeholder
import one.meshcore.shared.resources.onboarding_wifi_connection_port_clear_accessibility
import one.meshcore.shared.resources.onboarding_wifi_connection_port_placeholder
import one.meshcore.shared.resources.onboarding_wifi_connection_title
import one.meshcore.shared.resources.settings_wifi_edit_save_changes
import one.meshcore.shared.resources.settings_wifi_edit_title
import org.jetbrains.compose.resources.stringResource

internal const val DEFAULT_WIFI_PORT = "5000"

private val FIELD_GAP = 12.dp

/**
 * Host and port form shared by onboarding pair and Connect Radio.
 * Edit mode and a connection error are used only when changing a saved radio.
 */
@Composable
internal fun WiFiConnectDialog(
    onDismiss: () -> Unit,
    connecting: Boolean,
    onConnect: (host: String, port: Int) -> Unit,
    initialHost: String = "",
    initialPort: String = DEFAULT_WIFI_PORT,
    isEditing: Boolean = false,
    error: String? = null,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var portError by remember { mutableStateOf(false) }
    val portFocus = remember { FocusRequester() }

    fun submit() {
        val parsed = port.toIntOrNull()
        if (parsed == null || !WifiAddressValidator.isValidPort(port)) {
            portError = true
        } else {
            onConnect(host.trim(), parsed)
        }
    }

    FullScreenOverlay(
        onDismiss = { if (!connecting) onDismiss() },
        title = stringResource(
            if (isEditing) {
                Res.string.settings_wifi_edit_title
            } else {
                Res.string.onboarding_wifi_connection_title
            },
        ),
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInsetScroll(innerPadding)) {
            PreferenceHubSection(
                title = stringResource(Res.string.onboarding_wifi_connection_connection_details_header),
            ) {
                Column(
                    Modifier.padding(PreferenceHubCardPadding),
                    verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
                ) {
                    AppOutlinedTextField(
                        value = host,
                        onValueChange = { host = WifiAddressValidator.sanitizeHostInput(it) },
                        label = { Text(stringResource(Res.string.onboarding_wifi_connection_ip_address_placeholder)) },
                        singleLine = true,
                        keyboardOptions = WIFI_HOST_KEYBOARD,
                        keyboardActions = wifiHostKeyboardActions(portFocus),
                        enabled = !connecting,
                        trailingIcon = textFieldClearTrailingIcon(
                            value = host,
                            contentDescription = stringResource(
                                Res.string.onboarding_wifi_connection_ip_address_clear_accessibility,
                            ),
                            enabled = !connecting,
                            onClear = { host = "" },
                        ),
                    )
                    AppOutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            portError = false
                        },
                        label = { Text(stringResource(Res.string.onboarding_wifi_connection_port_placeholder)) },
                        singleLine = true,
                        isError = portError,
                        keyboardOptions = WIFI_PORT_KEYBOARD,
                        keyboardActions = wifiPortKeyboardActions(::submit),
                        modifier = Modifier.focusRequester(portFocus),
                        enabled = !connecting,
                        trailingIcon = textFieldClearTrailingIcon(
                            value = port,
                            contentDescription = stringResource(
                                Res.string.onboarding_wifi_connection_port_clear_accessibility,
                            ),
                            enabled = !connecting,
                            onClear = { port = "" },
                        ),
                    )
                    if (portError) {
                        FormErrorRow(
                            stringResource(Res.string.onboarding_wifi_connection_error_invalid_port),
                        )
                    } else {
                        error?.let { FormErrorRow(it) }
                    }
                }
            }
            PreferenceHubFooter(
                text = stringResource(Res.string.onboarding_wifi_connection_connection_details_footer),
            )
            Button(
                onClick = ::submit,
                enabled = WifiAddressValidator.isValidHost(host) && !connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding, vertical = PreferenceHubTopPadding),
            ) {
                if (connecting) {
                    FilledButtonProgress()
                }
                Text(
                    if (connecting) {
                        stringResource(Res.string.onboarding_wifi_connection_connecting)
                    } else if (isEditing) {
                        stringResource(Res.string.settings_wifi_edit_save_changes)
                    } else {
                        stringResource(Res.string.onboarding_wifi_connection_connect)
                    },
                )
            }
        }
    }
}
