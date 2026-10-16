package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_direct_messages_acknowledgments
import one.meshcore.shared.resources.settings_direct_messages_footer
import one.meshcore.shared.resources.settings_direct_messages_header
import one.meshcore.shared.resources.settings_telemetry_allow_requests
import one.meshcore.shared.resources.settings_telemetry_allow_requests_description
import one.meshcore.shared.resources.settings_telemetry_footer
import one.meshcore.shared.resources.settings_telemetry_header
import one.meshcore.shared.resources.settings_telemetry_include_environment
import one.meshcore.shared.resources.settings_telemetry_include_environment_description
import one.meshcore.shared.resources.settings_telemetry_include_location
import one.meshcore.shared.resources.settings_telemetry_include_location_description
import one.meshcore.shared.resources.settings_telemetry_manage_trusted
import one.meshcore.shared.resources.settings_telemetry_trusted_only
import one.meshcore.shared.resources.settings_telemetry_trusted_only_description
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot.Companion.TELEMETRY_MODE_EVERYONE
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot.Companion.TELEMETRY_MODE_OFF
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot.Companion.TELEMETRY_MODE_TRUSTED_ONLY
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced telemetry-sharing and direct-message multi-ack pickers, mirroring iOS
 * `TelemetrySettingsSection` / `DirectMessagesSettingsSection`. Path-hash lives on Settings → Radio.
 * Writes go through the connection layer; a retryable failure raises the shared [RetryAlert].
 */
@Composable
fun AdvancedOtherParamsSections(
    snapshot: DeviceOtherParamsSnapshot,
    enabled: Boolean,
    onSetTelemetryModes: suspend (base: Int, location: Int, environment: Int) -> Unit,
    onSetMultiAcks: suspend (Int) -> Unit,
    onOpenTrustedContacts: (() -> Unit)?,
    /** After the last retry, leave Advanced (iOS `dismiss()`). */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var local by remember(snapshot) { mutableStateOf(snapshot) }
    val retryAlert = rememberRetryAlertState()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TelemetrySection(
            snapshot = local,
            enabled = enabled,
            retryAlert = retryAlert,
            onSet = { base, loc, env ->
                onSetTelemetryModes(base, loc, env)
                local = local.copy(
                    telemetryModeBase = base,
                    telemetryModeLocation = loc,
                    telemetryModeEnvironment = env,
                )
            },
            onOpenTrustedContacts = onOpenTrustedContacts,
            onMaxRetriesExceeded = onMaxRetriesExceeded,
        )

        DirectMessagesSection(
            snapshot = local,
            enabled = enabled,
            retryAlert = retryAlert,
            onSet = { wire ->
                onSetMultiAcks(wire)
                local = local.copy(multiAcks = wire)
            },
            onMaxRetriesExceeded = onMaxRetriesExceeded,
        )
    }

    RetryAlert(retryAlert)
}

@Composable
private fun SectionFooter(text: String) {
    PreferenceHubFooter(text, applyHorizontalPadding = false)
}

@Composable
private fun TelemetrySection(
    snapshot: DeviceOtherParamsSnapshot,
    enabled: Boolean,
    retryAlert: RetryAlertState,
    onSet: suspend (base: Int, location: Int, environment: Int) -> Unit,
    onOpenTrustedContacts: (() -> Unit)?,
    onMaxRetriesExceeded: () -> Unit,
) {
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val rowEnabled = enabled && !isSaving

    val isFilterByTrusted = snapshot.telemetryModeBase == TELEMETRY_MODE_TRUSTED_ONLY
    val enabledMode = if (isFilterByTrusted) TELEMETRY_MODE_TRUSTED_ONLY else TELEMETRY_MODE_EVERYONE

    fun save(base: Int, location: Int, environment: Int) {
        launchOtherParamsSave(
            scope,
            OtherParamsSave(
                onSave = { onSet(base, location, environment) },
                onSaving = { isSaving = it },
                onError = { errorMessage = it },
                onCommitted = {},
                onWriteInFlight = {},
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    PreferenceHubSection(
        title = stringResource(Res.string.settings_telemetry_header),
        applyHorizontalPadding = false,
    ) {
        SettingsToggleRow(
            label = stringResource(Res.string.settings_telemetry_allow_requests),
            supporting = stringResource(Res.string.settings_telemetry_allow_requests_description),
            checked = snapshot.telemetryModeBase > TELEMETRY_MODE_OFF,
            enabled = rowEnabled,
            isRadioReady = enabled,
            onCheckedChange = { on ->
                save(
                    base = if (on) enabledMode else TELEMETRY_MODE_OFF,
                    location = snapshot.telemetryModeLocation,
                    environment = snapshot.telemetryModeEnvironment,
                )
            },
        )

        TelemetryDetailRows(
            snapshot = snapshot,
            rowEnabled = rowEnabled,
            enabled = enabled,
            enabledMode = enabledMode,
            isFilterByTrusted = isFilterByTrusted,
            onOpenTrustedContacts = onOpenTrustedContacts,
            onSave = ::save,
        )
    }
    SectionFooter(stringResource(Res.string.settings_telemetry_footer))
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

/**
 * The location / environment / trusted-only toggles plus the manage-trusted-contacts row, shown only
 * once base telemetry is on. Each toggle folds its change back through [onSave].
 */
@Composable
private fun TelemetryDetailRows(
    snapshot: DeviceOtherParamsSnapshot,
    rowEnabled: Boolean,
    enabled: Boolean,
    enabledMode: Int,
    isFilterByTrusted: Boolean,
    onOpenTrustedContacts: (() -> Unit)?,
    onSave: (base: Int, location: Int, environment: Int) -> Unit,
) {
    if (snapshot.telemetryModeBase <= TELEMETRY_MODE_OFF) return
    SettingsToggleRow(
        label = stringResource(Res.string.settings_telemetry_include_location),
        supporting = stringResource(Res.string.settings_telemetry_include_location_description),
        checked = snapshot.telemetryModeLocation > TELEMETRY_MODE_OFF,
        enabled = rowEnabled,
        isRadioReady = enabled,
        onCheckedChange = { on ->
            onSave(
                snapshot.telemetryModeBase,
                if (on) enabledMode else TELEMETRY_MODE_OFF,
                snapshot.telemetryModeEnvironment,
            )
        },
    )
    SettingsToggleRow(
        label = stringResource(Res.string.settings_telemetry_include_environment),
        supporting = stringResource(Res.string.settings_telemetry_include_environment_description),
        checked = snapshot.telemetryModeEnvironment > TELEMETRY_MODE_OFF,
        enabled = rowEnabled,
        isRadioReady = enabled,
        onCheckedChange = { on ->
            onSave(
                snapshot.telemetryModeBase,
                snapshot.telemetryModeLocation,
                if (on) enabledMode else TELEMETRY_MODE_OFF,
            )
        },
    )
    SettingsToggleRow(
        label = stringResource(Res.string.settings_telemetry_trusted_only),
        supporting = stringResource(Res.string.settings_telemetry_trusted_only_description),
        checked = isFilterByTrusted,
        enabled = rowEnabled,
        isRadioReady = enabled,
        onCheckedChange = { on ->
            val mode = if (on) TELEMETRY_MODE_TRUSTED_ONLY else TELEMETRY_MODE_EVERYONE
            onSave(
                if (snapshot.telemetryModeBase > TELEMETRY_MODE_OFF) mode else TELEMETRY_MODE_OFF,
                if (snapshot.telemetryModeLocation > TELEMETRY_MODE_OFF) mode else TELEMETRY_MODE_OFF,
                if (snapshot.telemetryModeEnvironment > TELEMETRY_MODE_OFF) mode else TELEMETRY_MODE_OFF,
            )
        },
    )
    if (isFilterByTrusted && onOpenTrustedContacts != null) {
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.settings_telemetry_manage_trusted))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpenTrustedContacts),
        )
    }
}

@Composable
private fun DirectMessagesSection(
    snapshot: DeviceOtherParamsSnapshot,
    enabled: Boolean,
    retryAlert: RetryAlertState,
    onSet: suspend (wire: Int) -> Unit,
    onMaxRetriesExceeded: () -> Unit,
) {
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Wire value 0/1 maps to the displayed 1/2 acknowledgments.
    val selected = snapshot.multiAcks + 1

    fun save(display: Int) {
        val wire = display - 1
        launchOtherParamsSave(
            scope,
            OtherParamsSave(
                onSave = { onSet(wire) },
                onSaving = { isSaving = it },
                onError = { errorMessage = it },
                onCommitted = {},
                onWriteInFlight = {},
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    PreferenceHubSection(
        title = stringResource(Res.string.settings_direct_messages_header),
        applyHorizontalPadding = false,
    ) {
        Column(Modifier.padding(PreferenceHubCardPadding)) {
            Text(
                text = stringResource(Res.string.settings_direct_messages_acknowledgments),
                style = MaterialTheme.typography.bodyLarge,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .radioConnectionHint(enabled),
            ) {
                DM_ACK_OPTIONS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selected == option,
                        onClick = { if (selected != option) save(option) },
                        enabled = enabled && !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DM_ACK_OPTIONS.size),
                        label = { Text(option.toString()) },
                    )
                }
            }
        }
    }
    SectionFooter(stringResource(Res.string.settings_direct_messages_footer))
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

private val DM_ACK_OPTIONS = listOf(1, 2)
