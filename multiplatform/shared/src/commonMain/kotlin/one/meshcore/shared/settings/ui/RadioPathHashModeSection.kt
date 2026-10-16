package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_path_hash_mode_footer
import one.meshcore.shared.resources.settings_path_hash_mode_header
import one.meshcore.shared.resources.settings_path_hash_mode_label
import one.meshcore.shared.resources.settings_path_hash_mode_one_byte
import one.meshcore.shared.resources.settings_path_hash_mode_three_bytes
import one.meshcore.shared.resources.settings_path_hash_mode_two_bytes
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PathHashModeSection(
    snapshot: DeviceOtherParamsSnapshot,
    enabled: Boolean,
    onSetPathHashMode: suspend (mode: Int) -> Unit,
    onMaxRetriesExceeded: () -> Unit,
    onWriteInFlight: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val retryAlert = rememberRetryAlertState()
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var localMode by remember(snapshot.pathHashMode) { mutableStateOf(snapshot.pathHashMode) }

    fun save(mode: Int) {
        launchOtherParamsSave(
            scope,
            OtherParamsSave(
                onSave = { onSetPathHashMode(mode) },
                onSaving = { isSaving = it },
                onError = { errorMessage = it },
                onCommitted = { localMode = mode },
                onWriteInFlight = onWriteInFlight,
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_path_hash_mode_header),
            applyHorizontalPadding = false,
        ) {
            Column {
                Text(
                    text = stringResource(pathHashModePickerLabel()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(PreferenceHubCardPadding),
                )
                PATH_HASH_OPTIONS.forEach { option ->
                    ListItem(
                        headlineContent = { Text(pathHashModeLabel(option)) },
                        leadingContent = {
                            RadioButton(
                                selected = localMode == option,
                                onClick = null,
                                enabled = enabled && !isSaving,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .radioConnectionHint(enabled)
                            .selectable(
                                selected = localMode == option,
                                enabled = enabled && !isSaving,
                                role = Role.RadioButton,
                                onClick = { if (localMode != option) save(option) },
                            ),
                    )
                }
            }
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_path_hash_mode_footer),
            applyHorizontalPadding = false,
        )
    }
    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

@Composable
private fun pathHashModeLabel(mode: Int): String = when (mode) {
    0 -> stringResource(Res.string.settings_path_hash_mode_one_byte)
    1 -> stringResource(Res.string.settings_path_hash_mode_two_bytes)
    else -> stringResource(Res.string.settings_path_hash_mode_three_bytes)
}

private val PATH_HASH_OPTIONS = listOf(0, 1, 2)

/** iOS `PathHashModeSection` picker label: Hash Size, under the Path Hash Size header. */
internal fun pathHashModePickerLabel(): StringResource = Res.string.settings_path_hash_mode_label
